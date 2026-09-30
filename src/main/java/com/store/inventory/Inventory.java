package com.store.inventory;

import com.store.inventory.alert.FailSafeStockAlertListener;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.CategoryPolicies;
import com.store.inventory.domain.LowStockRule;
import com.store.inventory.persistence.InMemoryInventoryRepository;
import com.store.inventory.service.ReservationService;
import java.time.Clock;
import java.util.Objects;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 *
 * <p>Es la <b>fábrica</b> y el único lugar que decide qué implementaciones se usan (composition
 * root). Cambiar el repositorio en memoria por uno de base de datos, o las reglas por categoría, es
 * cambiar una línea aquí; el resto del código depende de interfaces.</p>
 *
 * <pre>
 *  Inventory.create(clock, listener)
 *     └─ ReservationService
 *          ├─ InMemoryInventoryRepository     (InventoryRepository)
 *          ├─ CategoryPolicies.defaults()     (reglas de la tabla del README)
 *          ├─ LowStockRule.DEFAULT            (avisar con ≤ 5 disponibles)
 *          ├─ clock
 *          └─ FailSafeStockAlertListener(listener)
 * </pre>
 */
public final class Inventory {

    private Inventory() {
    }

    /**
     * @param clock         fuente de la hora
     * @param alertListener destino de los avisos de stock bajo
     * @return un servicio de inventario listo para usar
     */
    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");
        return new ReservationService(
                new InMemoryInventoryRepository(),
                CategoryPolicies.defaults(),
                LowStockRule.DEFAULT,
                clock,
                new FailSafeStockAlertListener(alertListener));
    }
}
