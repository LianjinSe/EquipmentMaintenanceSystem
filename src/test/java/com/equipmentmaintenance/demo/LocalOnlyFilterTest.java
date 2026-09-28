package com.equipmentmaintenance.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalOnlyFilterTest {
    @Test
    void allowsLocalRequestsAndRejectsRemoteAddresses() {
        assertTrue(LocalOnlyFilter.isLoopback("127.0.0.1"));
        assertTrue(LocalOnlyFilter.isLoopback("::1"));
        assertFalse(LocalOnlyFilter.isLoopback("192.168.1.100"));
        assertFalse(LocalOnlyFilter.isLoopback("203.0.113.20"));
        assertFalse(LocalOnlyFilter.isLoopback("invalid-address"));
    }
}
