package com.store.inventory.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.StockAlertListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockAlertListenersTest {

    @Test
    void compositeDeliversToEveryChannelInOrder() {
        List<String> delivered = new ArrayList<>();
        StockAlertListener email = (sku, available) -> delivered.add("email " + sku + ":" + available);
        StockAlertListener slack = (sku, available) -> delivered.add("slack " + sku + ":" + available);

        new CompositeStockAlertListener(List.of(email, slack)).onLowStock("SKU-1", 3);

        assertEquals(List.of("email SKU-1:3", "slack SKU-1:3"), delivered);
    }

    @Test
    void aFailingChannelDoesNotStopTheOthersWhenWrapped() {
        List<String> delivered = new ArrayList<>();
        StockAlertListener broken = (sku, available) -> {
            throw new IllegalStateException("down");
        };
        StockAlertListener slack = (sku, available) -> delivered.add("slack " + sku);

        new CompositeStockAlertListener(List.of(
                new FailSafeStockAlertListener(broken),
                new FailSafeStockAlertListener(slack))).onLowStock("SKU-1", 3);

        assertEquals(List.of("slack SKU-1"), delivered);
    }
}
