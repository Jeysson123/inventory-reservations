package com.store.inventory.service;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.CategoryPolicies;
import com.store.inventory.domain.LowStockRule;
import com.store.inventory.persistence.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Implementación de {@link InventoryService}: valida la entrada, toma la hora del {@link Clock} y
 * coordina el repositorio, las reglas de categoría y los avisos. Las reglas de negocio en sí están
 * en {@link com.store.inventory.domain.ProductStock}.
 *
 * <h2>Flujo de una reserva</h2>
 * <pre>
 *  reserve(order, sku, qty)
 *    │ 1. valida argumentos                         → IllegalArgumentException
 *    │ 2. repository.withProduct(sku, ...)           (acceso exclusivo al producto)
 *    │      ├─ producto desconocido                  → InsufficientStockException (0 disponibles)
 *    │      ├─ pedido ya usado con otro producto     → IllegalStateException
 *    │      ├─ product.reserve(..., política)        → OrderLimitExceededException
 *    │      │                                          InsufficientStockException
 *    │      └─ product.claimLowStockAlert(...)       ¿toca avisar a compras?
 *    │ 3. fuera del lock: aviso a compras si corresponde
 *    ▼
 *  Reservation
 * </pre>
 *
 * <p>El aviso se envía <b>después</b> de soltar el producto: si el correo tarda, los demás pedidos
 * de ese producto no esperan.</p>
 */
public final class ReservationService implements InventoryService {

    private final InventoryRepository repository;
    private final CategoryPolicies policies;
    private final LowStockRule lowStockRule;
    private final Clock clock;
    private final StockAlertListener alertListener;

    /**
     * @param repository    dónde vive el inventario
     * @param policies      reglas por categoría
     * @param lowStockRule  cuándo avisar a compras
     * @param clock         fuente de la hora (inyectada para poder probar vencimientos)
     * @param alertListener destino de los avisos de stock bajo
     */
    public ReservationService(InventoryRepository repository, CategoryPolicies policies, LowStockRule lowStockRule,
                              Clock clock, StockAlertListener alertListener) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.lowStockRule = Objects.requireNonNull(lowStockRule, "lowStockRule");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        Arguments.requireText(sku, "sku");
        Arguments.requireNonNull(category, "category");
        repository.register(sku, category);
    }

    @Override
    public void addStock(String sku, int quantity) {
        Arguments.requireText(sku, "sku");
        Arguments.requirePositive(quantity, "quantity");
        repository.withProduct(sku, product -> {
            product.addStock(quantity);
            return product;
        }).orElseThrow(() -> new IllegalArgumentException("Product " + sku + " is not registered"));
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        Arguments.requireText(orderId, "orderId");
        Arguments.requireText(sku, "sku");
        Arguments.requirePositive(quantity, "quantity");
        Instant now = clock.instant();

        ReserveOutcome outcome = repository.withProduct(sku, product -> {
            ensureOrderBelongsTo(orderId, sku);
            Reservation reservation = product.reserve(orderId, quantity, now, policies.of(product.category()));
            boolean alert = product.claimLowStockAlert(lowStockRule, now);
            return new ReserveOutcome(reservation, alert, product.available(now));
        }).orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));

        if (outcome.alertPurchasing()) {
            alertListener.onLowStock(sku, outcome.availableAfter());
        }
        return outcome.reservation();
    }

    @Override
    public void confirm(String orderId) {
        Arguments.requireText(orderId, "orderId");
        Instant now = clock.instant();
        String sku = repository.findSkuByOrder(orderId)
                .orElseThrow(() -> new IllegalStateException("Order " + orderId + " has no active reservation"));
        repository.withProduct(sku, product -> {
            product.confirm(orderId, now);
            return product;
        });
    }

    @Override
    public int available(String sku) {
        Arguments.requireText(sku, "sku");
        Instant now = clock.instant();
        return repository.withProduct(sku, product -> product.available(now)).orElse(0);
    }

    private void ensureOrderBelongsTo(String orderId, String sku) {
        String owner = repository.claimOrder(orderId, sku);
        if (!owner.equals(sku)) {
            throw new IllegalStateException("Order " + orderId + " already reserved product " + owner
                    + "; each order reserves a single product");
        }
    }

    /**
     * Lo que sale del bloque exclusivo del producto, para actuar después de soltarlo.
     *
     * @param reservation     la reserva creada o reintentada
     * @param alertPurchasing si hay que avisar a compras
     * @param availableAfter  unidades disponibles tras la reserva (va en el aviso)
     */
    private record ReserveOutcome(Reservation reservation, boolean alertPurchasing, int availableAfter) {
    }
}
