package com.store.inventory.persistence;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * {@link InventoryRepository} en memoria, seguro para muchos hilos dentro de una sola instancia.
 *
 * <p>Cada {@link ProductStock} actúa como su propio lock ({@code synchronized}). Los mapas son
 * {@link ConcurrentHashMap}, así que buscar un producto nunca bloquea a otro.</p>
 *
 * <p>No sirve para varias instancias del servicio: cada una tendría su propio mapa. Para eso está
 * la implementación con base de datos descrita en {@code DECISIONS.md}.</p>
 */
public final class InMemoryInventoryRepository implements InventoryRepository {

    private final ConcurrentMap<String, ProductStock> products = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> orderOwners = new ConcurrentHashMap<>();

    @Override
    public void register(String sku, ProductCategory category) {
        ProductStock product = products.computeIfAbsent(sku, key -> new ProductStock(key, category));
        synchronized (product) {
            product.changeCategory(category);
        }
    }

    @Override
    public <R> Optional<R> withProduct(String sku, Function<ProductStock, R> action) {
        ProductStock product = products.get(sku);
        if (product == null) {
            return Optional.empty();
        }
        synchronized (product) {
            return Optional.of(Objects.requireNonNull(action.apply(product), "action must not return null"));
        }
    }

    @Override
    public String claimOrder(String orderId, String sku) {
        String owner = orderOwners.putIfAbsent(orderId, sku);
        return owner == null ? sku : owner;
    }

    @Override
    public Optional<String> findSkuByOrder(String orderId) {
        return Optional.ofNullable(orderOwners.get(orderId));
    }
}
