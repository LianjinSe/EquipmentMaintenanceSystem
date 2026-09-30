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

    @Test
    void acceptsOnlyLiteralLocalHostHeaders() {
        assertTrue(LocalOnlyFilter.isLocalHostHeader("localhost:8080"));
        assertTrue(LocalOnlyFilter.isLocalHostHeader("LOCALHOST"));
        assertTrue(LocalOnlyFilter.isLocalHostHeader("127.0.0.1:8080"));
        assertTrue(LocalOnlyFilter.isLocalHostHeader("[::1]:8080"));
        assertFalse(LocalOnlyFilter.isLocalHostHeader("evil.example:8080"));
        assertFalse(LocalOnlyFilter.isLocalHostHeader("127.0.0.1.evil.example"));
        assertFalse(LocalOnlyFilter.isLocalHostHeader("localhost:65536"));
        assertFalse(LocalOnlyFilter.isLocalHostHeader("localhost:0"));
        assertFalse(LocalOnlyFilter.isLocalHostHeader(null));
    }
}
