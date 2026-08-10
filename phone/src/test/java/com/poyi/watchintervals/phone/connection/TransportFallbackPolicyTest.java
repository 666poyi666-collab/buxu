package com.poyi.watchintervals.phone.connection;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.poyi.watchintervals.phone.connection.ConnectionState;

public class TransportFallbackPolicyTest {
    @Test public void onlyReadRequestsFallBackFromLanToBle() {
        assertTrue(TransportFallbackPolicy.shouldRetryOnBle("GET", TransportType.LAN, true));
        assertTrue(TransportFallbackPolicy.shouldRetryOnBle("get", TransportType.LAN, true));
        assertFalse(TransportFallbackPolicy.shouldRetryOnBle("DELETE", TransportType.LAN, true));
        assertFalse(TransportFallbackPolicy.shouldRetryOnBle("GET", TransportType.BLE, true));
        assertFalse(TransportFallbackPolicy.shouldRetryOnBle("GET", TransportType.LAN, false));
    }

    @Test public void fallbackReconnectsUnlessBleSessionIsAlreadyAuthenticated() {
        assertTrue(TransportFallbackPolicy.isBleSessionReady(ConnectionState.CONNECTED_BLE));
        assertTrue(TransportFallbackPolicy.isBleSessionReady(
                ConnectionState.CONNECTED_BLE_LAN));
        assertFalse(TransportFallbackPolicy.isBleSessionReady(ConnectionState.CONNECTED_LAN));
        assertFalse(TransportFallbackPolicy.isBleSessionReady(ConnectionState.SCANNING));
    }

    @Test public void fallbackUsesOnlyTheOriginalRequestsRemainingLifetime() {
        assertEquals(4_000L, TransportFallbackPolicy.remainingTtl(10_000L, 6_000L));
        assertEquals(0L, TransportFallbackPolicy.remainingTtl(10_000L, 10_001L));
        assertEquals(0L, TransportFallbackPolicy.remainingTtl(0L, 10_001L));
    }
}
