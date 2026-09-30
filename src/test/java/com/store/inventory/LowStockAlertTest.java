package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Avisos a compras: con 5 disponibles o menos, una sola vez hasta el próximo reabastecimiento.
class LowStockAlertTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final List<String> alerts = new CopyOnWriteArrayList<>();
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @Test
    void alertsWhenAvailableUnitsDropToFive() {
        service.reserve("ORDER-1", "SKU-1", 4);
        assertEquals(List.of(), alerts, "6 available is not low yet");

        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(List.of("SKU-1:5"), alerts);
    }

    @Test
    void doesNotRepeatTheAlertUntilRestocked() {
        service.reserve("ORDER-1", "SKU-1", 6);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 3);

        assertEquals(List.of("SKU-1:4"), alerts);
    }

    @Test
    void expiredReservationsDoNotCountAsRestock() {
        service.reserve("ORDER-1", "SKU-1", 6);
        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", "SKU-1", 6);

        assertEquals(List.of("SKU-1:4"), alerts);
    }

    @Test
    void restockingEnablesTheNextAlert() {
        service.reserve("ORDER-1", "SKU-1", 6);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(List.of("SKU-1:4", "SKU-1:4"), alerts);
    }

    @Test
    void addingStockNeverAlertsByItself() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 3);
        assertEquals(List.of(), alerts);

        service.reserve("ORDER-1", "SKU-2", 1);

        assertEquals(List.of("SKU-2:2"), alerts);
    }

    @Test
    void retriesAndFailedReservationsDoNotAlertAgain() {
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 5);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-3", "SKU-1", 1));

        assertEquals(List.of("SKU-1:5"), alerts);
    }

    @Test
    void confirmingDoesNotChangeAvailabilityNorAlert() {
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        assertEquals(6, service.available("SKU-1"));
        assertEquals(List.of(), alerts);
    }

    @Test
    void aFailingChannelDoesNotBreakTheReservation() {
        InventoryService failing = Inventory.create(clock, (sku, available) -> {
            throw new IllegalStateException("mail server down");
        });
        failing.registerProduct("SKU-1", ProductCategory.STANDARD);
        failing.addStock("SKU-1", 3);

        failing.reserve("ORDER-1", "SKU-1", 1);

        assertEquals(2, failing.available("SKU-1"));
    }
}
