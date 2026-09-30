package com.store.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class CategoryPoliciesTest {

    // La tabla del README, tal cual.
    @Test
    void defaultsMatchTheBusinessRules() {
        CategoryPolicies policies = CategoryPolicies.defaults();

        assertEquals(new CategoryPolicy(Duration.ofMinutes(15), OptionalInt.empty()), policies.of(ProductCategory.STANDARD));
        assertEquals(new CategoryPolicy(Duration.ofHours(24), OptionalInt.empty()), policies.of(ProductCategory.PRE_ORDER));
        assertEquals(new CategoryPolicy(Duration.ofMinutes(5), OptionalInt.of(2)), policies.of(ProductCategory.FLASH_SALE));
    }

    // Una categoría nueva sin reglas debe impedir que el servicio arranque.
    @Test
    void failsFastWhenACategoryHasNoRules() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, CategoryPolicy.unlimited(Duration.ofMinutes(15)))));

        assertTrue(error.getMessage().contains("PRE_ORDER"));
        assertTrue(error.getMessage().contains("FLASH_SALE"));
    }

    @Test
    void rejectsPoliciesThatMakeNoSense() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.unlimited(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.unlimited(Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.limitedTo(0, Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> new LowStockRule(-1));
    }

    @Test
    void lowStockRuleIncludesTheThreshold() {
        assertTrue(LowStockRule.DEFAULT.isLow(5));
        assertTrue(LowStockRule.DEFAULT.isLow(0));
        assertFalse(LowStockRule.DEFAULT.isLow(6));
    }
}
