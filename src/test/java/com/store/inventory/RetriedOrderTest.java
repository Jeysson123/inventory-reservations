package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// "Si la conexión es lenta, la app reenvía el pedido automáticamente": un reintento no reserva dos veces.
class RetriedOrderTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.addStock("SKU-2", 10);
    }

    @Test
    void retryReturnsTheSameReservationWithoutReservingAgain() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(1));

        Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, retry, "the retry does not extend the payment window");
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retryAfterPaymentDoesNotReserveAgain() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");

        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void sameOrderWithAnotherQuantityIsRejected() {
        service.reserve("ORDER-1", "SKU-1", 3);

        assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "SKU-1", 4));
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void sameOrderCannotReserveAnotherProduct() {
        service.reserve("ORDER-1", "SKU-1", 3);

        assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "SKU-2", 3));
        assertEquals(10, service.available("SKU-2"));
    }

    @Test
    void anExpiredOrderCanReserveAgain() {
        Reservation expired = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(15));

        Reservation renewed = service.reserve("ORDER-1", "SKU-1", 3);

        assertNotEquals(expired.expiresAt(), renewed.expiresAt());
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retryAfterAFailedAttemptIsEvaluatedAgain() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 11));

        service.addStock("SKU-1", 1);

        assertEquals(11, service.reserve("ORDER-1", "SKU-1", 11).quantity());
        assertEquals(0, service.available("SKU-1"));
    }
}
