package com.store.inventory.domain;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * El inventario de un producto y sus reservas. Aquí viven todas las reglas que cambian las unidades.
 *
 * <pre>
 *  onHand      unidades físicas que aún no se vendieron
 *  pending     reservas esperando pago (cuentan contra lo disponible hasta que vencen)
 *  confirmed   reservas pagadas (sus unidades ya salieron de onHand)
 *
 *  available = onHand − unidades en pending que no han vencido
 * </pre>
 *
 * <h2>Ciclo de vida de una reserva</h2>
 * <pre>
 *  reserve() ──► PENDIENTE ──confirm()──► CONFIRMADA   (unidades vendidas, nunca vuelven)
 *                    │
 *                    └──llega expiresAt──► VENCIDA      (unidades disponibles otra vez)
 * </pre>
 *
 * <p>Una reserva vence en el instante exacto {@code expiresAt}: a esa hora el cliente ya no está
 * "a tiempo". Las vencidas no se borran con un temporizador; se ignoran al calcular lo disponible y
 * se limpian en la siguiente operación que modifica el producto.</p>
 *
 * <p><b>No es thread-safe.</b> Solo se modifica dentro de
 * {@link com.store.inventory.persistence.InventoryRepository#withProduct}, que garantiza acceso
 * exclusivo. Así esta clase se lee como lógica de negocio pura, sin locks.</p>
 */
public final class ProductStock {

    private final String sku;
    private ProductCategory category;
    private int onHand;
    private final Map<String, Reservation> pending = new HashMap<>();
    private final Map<String, Reservation> confirmed = new HashMap<>();
    private boolean lowStockAlertSent;

    /**
     * @param sku      identificador del producto
     * @param category categoría que define sus reglas de reserva
     */
    public ProductStock(String sku, ProductCategory category) {
        this.sku = Objects.requireNonNull(sku, "sku");
        this.category = Objects.requireNonNull(category, "category");
    }

    /** @return el identificador del producto */
    public String sku() {
        return sku;
    }

    /** @return la categoría actual */
    public ProductCategory category() {
        return category;
    }

    /**
     * Cambia la categoría. Las reservas ya hechas conservan su vencimiento; las nuevas usan las
     * reglas de la nueva categoría.
     *
     * @param category nueva categoría
     */
    public void changeCategory(ProductCategory category) {
        this.category = Objects.requireNonNull(category, "category");
    }

    /**
     * Ingresa unidades al almacén. Cuenta como reabastecimiento: vuelve a habilitar el aviso de
     * stock bajo para la próxima vez que el producto baje del umbral.
     *
     * @param quantity unidades a sumar (ya validadas como positivas)
     * @throws IllegalArgumentException si el stock superaría {@link Integer#MAX_VALUE}
     */
    public void addStock(int quantity) {
        try {
            onHand = Math.addExact(onHand, quantity);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Stock for " + sku + " would exceed " + Integer.MAX_VALUE + " units");
        }
        lowStockAlertSent = false;
    }

    /**
     * Unidades que todavía se pueden reservar.
     *
     * @param now instante actual
     * @return stock menos las reservas pendientes que no han vencido
     */
    public int available(Instant now) {
        int reserved = pending.values().stream()
                .filter(reservation -> isActive(reservation, now))
                .mapToInt(Reservation::quantity)
                .sum();
        return onHand - reserved;
    }

    /**
     * Aparta unidades para un pedido.
     *
     * <p><b>Idempotente por {@code orderId}:</b> la app reenvía el pedido si la conexión es lenta,
     * así que el mismo pedido puede llegar varias veces. Si ya tiene una reserva vigente o
     * confirmada, se devuelve esa misma reserva sin apartar unidades de nuevo.</p>
     *
     * <pre>
     *  ¿supera el límite de la categoría? ─ sí ─► OrderLimitExceededException
     *  ¿el pedido ya tiene reserva vigente o confirmada?
     *        ├─ misma cantidad ─► devuelve la reserva existente (reintento)
     *        └─ otra cantidad  ─► IllegalStateException (no es un reintento, es otro pedido)
     *  ¿hay suficientes disponibles? ─ no ─► InsufficientStockException
     *  crea la reserva con vencimiento = ahora + ventana de pago de la categoría
     * </pre>
     *
     * @param orderId  pedido que reserva
     * @param quantity unidades (ya validadas como positivas)
     * @param now      instante actual
     * @param policy   reglas de la categoría del producto
     * @return la reserva creada, o la existente si es un reintento
     */
    public Reservation reserve(String orderId, int quantity, Instant now, CategoryPolicy policy) {
        policy.checkOrderLimit(sku, quantity);
        releaseExpired(now);

        Reservation existing = pending.getOrDefault(orderId, confirmed.get(orderId));
        if (existing != null) {
            return sameRequestOrFail(existing, quantity);
        }

        int available = available(now);
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(policy.paymentWindow()));
        pending.put(orderId, reservation);
        return reservation;
    }

    /**
     * Marca como vendida la reserva de un pedido pagado. Sus unidades salen del stock para siempre.
     *
     * @param orderId pedido pagado
     * @param now     instante actual
     * @throws IllegalStateException si el pedido no tiene una reserva vigente (no existe, venció o
     *                               ya se confirmó)
     */
    public void confirm(String orderId, Instant now) {
        releaseExpired(now);
        Reservation reservation = pending.remove(orderId);
        if (reservation == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation for " + sku);
        }
        onHand -= reservation.quantity();
        confirmed.put(orderId, reservation);
    }

    /**
     * Decide si hay que avisar a compras <b>ahora</b> y, si es así, lo deja anotado para no repetir
     * el aviso hasta el próximo reabastecimiento ({@link #addStock}).
     *
     * <p>Consultar y marcar ocurre en el mismo paso: aunque cientos de pedidos crucen el umbral a la
     * vez, solo uno recibe {@code true}.</p>
     *
     * @param rule umbral de stock bajo
     * @param now  instante actual
     * @return {@code true} si este es el momento de enviar el aviso
     */
    public boolean claimLowStockAlert(LowStockRule rule, Instant now) {
        if (lowStockAlertSent || !rule.isLow(available(now))) {
            return false;
        }
        lowStockAlertSent = true;
        return true;
    }

    private Reservation sameRequestOrFail(Reservation existing, int quantity) {
        if (existing.quantity() != quantity) {
            throw new IllegalStateException("Order " + existing.orderId() + " already reserved "
                    + existing.quantity() + " units of " + sku + ", cannot change it to " + quantity);
        }
        return existing;
    }

    private void releaseExpired(Instant now) {
        pending.values().removeIf(reservation -> !isActive(reservation, now));
    }

    private static boolean isActive(Reservation reservation, Instant now) {
        return now.isBefore(reservation.expiresAt());
    }
}
