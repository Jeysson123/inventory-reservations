package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * <b>Decorador</b> que evita que un aviso fallido tumbe una venta.
 *
 * <p>Los avisos a compras son un efecto secundario: si el servidor de correo está caído, el cliente
 * igual debe poder reservar. Este decorador registra el error y deja seguir el flujo.</p>
 *
 * <pre>
 *  ReservationService ──► FailSafeStockAlertListener ──► listener real (correo, Slack, ...)
 *                                  └─ si falla: log WARNING, la reserva sigue siendo válida
 * </pre>
 *
 * <p>Límite conocido: el aviso que falla se pierde. En producción se resolvería con un outbox
 * (ver {@code DECISIONS.md}).</p>
 */
public final class FailSafeStockAlertListener implements StockAlertListener {

    private static final Logger LOG = System.getLogger(FailSafeStockAlertListener.class.getName());

    private final StockAlertListener delegate;

    /**
     * @param delegate listener que entrega el aviso de verdad
     */
    public FailSafeStockAlertListener(StockAlertListener delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onLowStock(String sku, int availableUnits) {
        try {
            delegate.onLowStock(sku, availableUnits);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Low stock alert for " + sku + " (" + availableUnits + " units) could not be delivered", e);
        }
    }
}
