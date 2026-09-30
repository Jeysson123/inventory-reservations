package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import java.util.List;

/**
 * <b>Composite</b>: reparte un mismo aviso a varios canales.
 *
 * <p>Hoy compras recibe avisos por correo y quiere sumar más canales. El servicio solo conoce un
 * {@link StockAlertListener}; para agregar un canal no se toca el servicio, se agrega un listener a
 * esta lista al construir el inventario:</p>
 *
 * <pre>
 *  Inventory.create(clock, new CompositeStockAlertListener(List.of(
 *          new FailSafeStockAlertListener(emailListener),
 *          new FailSafeStockAlertListener(slackListener))));
 * </pre>
 *
 * <p>Envuelve cada canal en {@link FailSafeStockAlertListener} para que la caída de uno no impida
 * que los demás reciban el aviso.</p>
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private final List<StockAlertListener> channels;

    /**
     * @param channels canales que recibirán cada aviso, en orden
     */
    public CompositeStockAlertListener(List<StockAlertListener> channels) {
        this.channels = List.copyOf(channels);
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        channels.forEach(channel -> channel.onLowStock(sku, availableUnits));
    }
}
