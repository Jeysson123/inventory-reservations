package com.store.inventory.domain;

/**
 * Cuándo un producto se considera con stock bajo y compras debe enterarse.
 *
 * <p>Está separada de {@link ProductStock} para que cambiar el umbral (o darle uno distinto a cada
 * producto en el futuro) no obligue a tocar la lógica de reservas.</p>
 *
 * @param threshold unidades disponibles a partir de las cuales (inclusive) se avisa
 */
public record LowStockRule(int threshold) {

    /** Regla acordada con compras: avisar con 5 unidades disponibles o menos. */
    public static final LowStockRule DEFAULT = new LowStockRule(5);

    /**
     * @throws IllegalArgumentException si el umbral es negativo
     */
    public LowStockRule {
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative, was " + threshold);
        }
    }

    /**
     * @param availableUnits unidades disponibles del producto
     * @return {@code true} si hay que avisar a compras
     */
    public boolean isLow(int availableUnits) {
        return availableUnits <= threshold;
    }
}
