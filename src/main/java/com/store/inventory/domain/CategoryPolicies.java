package com.store.inventory.domain;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tabla de reglas por categoría: el único lugar del código que conoce las categorías.
 *
 * <h2>Cómo agregar la categoría de la próxima temporada</h2>
 * <ol>
 *   <li>El nuevo valor llega en {@code ProductCategory} (paquete {@code api}).</li>
 *   <li>Se agrega una línea en {@link #defaults()} con su tiempo para pagar y su límite.</li>
 * </ol>
 * <p>Si alguien hace el paso 1 y olvida el 2, el servicio <b>no arranca</b>: el constructor
 * detecta la categoría sin reglas. Es preferible fallar al desplegar que reservar con reglas
 * inventadas en plena temporada alta.</p>
 */
public final class CategoryPolicies {

    private final Map<ProductCategory, CategoryPolicy> policies;

    /**
     * @param policies una política por cada valor de {@link ProductCategory}
     * @throws IllegalStateException si alguna categoría no tiene política
     */
    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        Objects.requireNonNull(policies, "policies");
        List<ProductCategory> missing = Arrays.stream(ProductCategory.values())
                .filter(category -> !policies.containsKey(category))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No reservation rules configured for categories " + missing);
        }
        this.policies = new EnumMap<>(policies);
    }

    /**
     * Las reglas acordadas con negocio (tabla del README).
     *
     * @return las políticas por defecto
     */
    public static CategoryPolicies defaults() {
        return new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)),
                // Se paga por transferencia, por eso el plazo es largo.
                ProductCategory.PRE_ORDER, CategoryPolicy.unlimited(Duration.ofHours(24)),
                ProductCategory.FLASH_SALE, CategoryPolicy.limitedTo(2, Duration.ofMinutes(5))));
    }

    /**
     * @param category categoría del producto
     * @return sus reglas
     */
    public CategoryPolicy of(ProductCategory category) {
        return policies.get(category);
    }
}
