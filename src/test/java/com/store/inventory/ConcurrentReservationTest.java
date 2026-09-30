package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;

// Temporada alta: cientos de clientes compran el mismo producto al mismo tiempo.
class ConcurrentReservationTest {

    private static final int THREADS = 32;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));

    @RepeatedTest(5)
    void neverSellsMoreUnitsThanInStock() throws Exception {
        AtomicInteger alerts = new AtomicInteger();
        InventoryService service = Inventory.create(clock, (sku, available) -> alerts.incrementAndGet());
        service.registerProduct("SKU-HOT", ProductCategory.FLASH_SALE);
        service.addStock("SKU-HOT", 50);

        List<Callable<Boolean>> buyers = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String orderId = "ORDER-" + i;
            buyers.add(() -> {
                try {
                    service.reserve(orderId, "SKU-HOT", 1);
                    return true;
                } catch (InsufficientStockException soldOut) {
                    return false;
                }
            });
        }

        long successful = runAllAtOnce(buyers).stream().filter(Boolean::booleanValue).count();

        assertEquals(50, successful);
        assertEquals(0, service.available("SKU-HOT"));
        assertEquals(1, alerts.get(), "purchasing is alerted exactly once");
    }

    @RepeatedTest(5)
    void simultaneousRetriesOfTheSameOrderReserveOnce() throws Exception {
        InventoryService service = Inventory.create(clock, (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        List<Callable<Reservation>> retries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            retries.add(() -> service.reserve("ORDER-1", "SKU-1", 3));
        }

        List<Reservation> results = runAllAtOnce(retries);

        assertEquals(1, new HashSet<>(results).size());
        assertEquals(7, service.available("SKU-1"));
    }

    // Suelta todas las tareas a la vez con una "puerta" para maximizar la competencia.
    private static <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
