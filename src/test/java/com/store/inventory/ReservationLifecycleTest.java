package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

// Criterios del README: reservar, liberar si no paga a tiempo, confirmar al pagar.
class ReservationLifecycleTest {

    private static final String STANDARD = "SKU-STANDARD";
    private static final String PRE_ORDER = "SKU-PRE-ORDER";
    private static final String FLASH = "SKU-FLASH";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct(STANDARD, ProductCategory.STANDARD);
        service.registerProduct(PRE_ORDER, ProductCategory.PRE_ORDER);
        service.registerProduct(FLASH, ProductCategory.FLASH_SALE);
        service.addStock(STANDARD, 100);
        service.addStock(PRE_ORDER, 100);
        service.addStock(FLASH, 100);
    }

    @ParameterizedTest(name = "{0} gives {1} to pay")
    @CsvSource({
            "SKU-STANDARD,  PT15M",
            "SKU-PRE-ORDER, PT24H",
            "SKU-FLASH,     PT5M"
    })
    void reservationExpiresAfterThePaymentWindowOfItsCategory(String sku, Duration window) {
        Reservation reservation = service.reserve("ORDER-1", sku, 2);

        assertEquals(new Reservation("ORDER-1", sku, 2, clock.instant().plus(window)), reservation);
        clock.advance(window.minusMillis(1));
        assertEquals(98, service.available(sku), "still reserved one millisecond before expiring");
        clock.advance(Duration.ofMillis(1));
        assertEquals(100, service.available(sku), "released exactly at expiresAt");
    }

    @Test
    void releasedUnitsCanBeBoughtByOtherCustomers() {
        service.registerProduct("SKU-LAST", ProductCategory.STANDARD);
        service.addStock("SKU-LAST", 1);
        service.reserve("ORDER-1", "SKU-LAST", 1);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-LAST", 1));

        clock.advance(Duration.ofMinutes(15));
        service.reserve("ORDER-2", "SKU-LAST", 1);
        assertEquals(0, service.available("SKU-LAST"));
    }

    @Test
    void confirmedUnitsNeverReturnToStock() {
        service.reserve("ORDER-1", STANDARD, 2);
        service.confirm("ORDER-1");

        clock.advance(Duration.ofDays(30));

        assertEquals(98, service.available(STANDARD));
    }

    @Test
    void cannotConfirmAfterThePaymentWindow() {
        service.reserve("ORDER-1", FLASH, 1);
        clock.advance(Duration.ofMinutes(5));

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(100, service.available(FLASH));
    }

    @Test
    void cannotConfirmUnknownOrders() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NO-SUCH-ORDER"));
    }

    @Test
    void cannotConfirmTwice() {
        service.reserve("ORDER-1", STANDARD, 2);
        service.confirm("ORDER-1");

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(98, service.available(STANDARD));
    }

    @Test
    void flashSaleAllowsAtMostTwoUnitsPerOrder() {
        service.reserve("ORDER-1", FLASH, 2);

        OrderLimitExceededException error =
                assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-2", FLASH, 3));
        assertEquals("Order limit for SKU-FLASH is 2 units, requested 3", error.getMessage());
        assertEquals(98, service.available(FLASH));
    }

    @Test
    void orderLimitIsCheckedBeforeStock() {
        service.registerProduct("SKU-FLASH-EMPTY", ProductCategory.FLASH_SALE);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-FLASH-EMPTY", 3));
    }

    @Test
    void standardAndPreOrderHaveNoLimitPerOrder() {
        service.reserve("ORDER-1", STANDARD, 100);
        service.reserve("ORDER-2", PRE_ORDER, 100);

        assertEquals(0, service.available(STANDARD));
        assertEquals(0, service.available(PRE_ORDER));
    }

    @Test
    void unknownProductsHaveNoUnits() {
        assertEquals(0, service.available("SKU-UNKNOWN"));
        InsufficientStockException error =
                assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-UNKNOWN", 1));
        assertEquals("Insufficient stock for SKU-UNKNOWN: requested 1, available 0", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void quantitiesMustBePositive(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", STANDARD, quantity));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-UNKNOWN", quantity));
        assertThrows(IllegalArgumentException.class, () -> service.addStock(STANDARD, quantity));
    }

    @Test
    void stockCanOnlyBeAddedToRegisteredProducts() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-UNKNOWN", 5));
    }

    @Test
    void rejectsBlankIdentifiersAndMissingCategory() {
        assertThrows(IllegalArgumentException.class, () -> service.reserve(" ", STANDARD, 1));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", null, 1));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("", ProductCategory.STANDARD));
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("SKU-X", null));
        assertThrows(IllegalArgumentException.class, () -> service.confirm(null));
    }

    @Test
    void stockCannotOverflow() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock(STANDARD, Integer.MAX_VALUE));
        assertEquals(100, service.available(STANDARD));
    }

    @Test
    void registeringAgainChangesTheRulesButKeepsStockAndReservations() {
        service.reserve("ORDER-1", STANDARD, 10);

        service.registerProduct(STANDARD, ProductCategory.FLASH_SALE);

        assertEquals(90, service.available(STANDARD));
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-2", STANDARD, 3));
        Reservation flash = service.reserve("ORDER-3", STANDARD, 2);
        assertEquals(clock.instant().plus(Duration.ofMinutes(5)), flash.expiresAt());
    }
}
