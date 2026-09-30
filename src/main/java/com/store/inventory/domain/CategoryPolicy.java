package com.store.inventory.domain;

import com.store.inventory.api.OrderLimitExceededException;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Reglas de negocio de una categoría de producto: cuánto tiempo tiene el cliente para pagar y
 * cuántas unidades puede llevar en un solo pedido.
 *
 * <p>Es la <b>estrategia</b> que cambia de una categoría a otra. El resto del código nunca pregunta
 * "¿es FLASH_SALE?": le pide la política a {@link CategoryPolicies} y la aplica. Así, una categoría
 * nueva de Marketing es una línea de configuración y no un {@code if} más repartido por el código.</p>
 *
 * <pre>
 *  STANDARD   → CategoryPolicy.unlimited(15 min)
 *  PRE_ORDER  → CategoryPolicy.unlimited(24 h)
 *  FLASH_SALE → CategoryPolicy.limitedTo(2, 5 min)
 * </pre>
 *
 * @param paymentWindow     tiempo que dura la reserva antes de liberarse si no se paga
 * @param maxUnitsPerOrder  unidades máximas por pedido; vacío significa "sin límite"
 */
public record CategoryPolicy(Duration paymentWindow, OptionalInt maxUnitsPerOrder) {

    /**
     * Valida que la política tenga sentido al crearla, no al usarla.
     *
     * @throws IllegalArgumentException si la ventana no es positiva o el límite es menor que 1
     */
    public CategoryPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        Objects.requireNonNull(maxUnitsPerOrder, "maxUnitsPerOrder");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive, was " + paymentWindow);
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() < 1) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be at least 1, was " + maxUnitsPerOrder.getAsInt());
        }
    }

    /**
     * Categoría sin límite de unidades por pedido.
     *
     * @param paymentWindow tiempo para pagar
     * @return la política
     */
    public static CategoryPolicy unlimited(Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, OptionalInt.empty());
    }

    /**
     * Categoría con un máximo de unidades por pedido.
     *
     * @param maxUnitsPerOrder unidades máximas en un pedido
     * @param paymentWindow    tiempo para pagar
     * @return la política
     */
    public static CategoryPolicy limitedTo(int maxUnitsPerOrder, Duration paymentWindow) {
        return new CategoryPolicy(paymentWindow, OptionalInt.of(maxUnitsPerOrder));
    }

    /**
     * Rechaza el pedido si supera el límite de la categoría.
     *
     * @param sku      producto pedido (solo para el mensaje de error)
     * @param quantity unidades pedidas
     * @throws OrderLimitExceededException si la categoría no permite tantas unidades por pedido
     */
    public void checkOrderLimit(String sku, int quantity) {
        if (maxUnitsPerOrder.isPresent() && quantity > maxUnitsPerOrder.getAsInt()) {
            throw new OrderLimitExceededException(sku, quantity, maxUnitsPerOrder.getAsInt());
        }
    }
}
