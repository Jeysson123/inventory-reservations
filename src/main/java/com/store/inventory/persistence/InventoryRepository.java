package com.store.inventory.persistence;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.util.Optional;
import java.util.function.Function;

/**
 * Dónde se guarda el inventario. El servicio depende de esta interfaz y no de un mapa en memoria,
 * para que la migración a base de datos sea una implementación nueva y no una reescritura.
 *
 * <h2>El contrato importante: {@link #withProduct}</h2>
 * <p>Toda lectura o cambio de un producto pasa por ahí, con acceso <b>exclusivo</b> a ese producto
 * mientras dura la función. Eso es lo que impide la sobreventa: dos pedidos del mismo producto no
 * pueden leer "quedan 3" al mismo tiempo. Pedidos de productos distintos no se bloquean entre sí.</p>
 *
 * <pre>
 *  En memoria (hoy)            → un lock por producto (InMemoryInventoryRepository)
 *  Base de datos (varias instancias)
 *                              → una transacción con SELECT ... FOR UPDATE sobre la fila del
 *                                producto, o un UPDATE condicional con número de versión
 * </pre>
 */
public interface InventoryRepository {

    /**
     * Crea el producto, o actualiza su categoría si ya existe (conserva stock y reservas).
     *
     * @param sku      identificador del producto
     * @param category su categoría
     */
    void register(String sku, ProductCategory category);

    /**
     * Ejecuta {@code action} con acceso exclusivo al producto.
     *
     * <p>{@code action} no debe llamar a código externo lento (correo, red): mientras corre, los
     * demás pedidos de ese producto esperan.</p>
     *
     * @param sku    producto
     * @param action lectura o cambio a aplicar; no debe devolver {@code null}
     * @param <R>    lo que devuelve la acción
     * @return el resultado de la acción, o vacío si el producto no está registrado
     */
    <R> Optional<R> withProduct(String sku, Function<ProductStock, R> action);

    /**
     * Asocia un pedido a un producto la primera vez que se ve. Cada pedido reserva un solo
     * producto, así que un mismo {@code orderId} no puede usarse después para otro producto.
     *
     * @param orderId pedido
     * @param sku     producto que el pedido intenta reservar
     * @return el producto al que quedó asociado el pedido: {@code sku} si es nuevo, o el que ya tenía
     */
    String claimOrder(String orderId, String sku);

    /**
     * @param orderId pedido
     * @return el producto que reservó ese pedido, si alguna vez reservó
     */
    Optional<String> findSkuByOrder(String orderId);
}
