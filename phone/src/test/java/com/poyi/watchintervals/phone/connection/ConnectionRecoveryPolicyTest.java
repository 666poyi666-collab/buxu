package com.poyi.watchintervals.phone.connection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectionRecoveryPolicyTest {
    @Test public void connectedBleAlwaysShortCircuitsRepeatedConnectCalls() {
        assertTrue(ConnectionRecoveryPolicy.isBleReady(ConnectionState.CONNECTED_BLE));
        assertTrue(ConnectionRecoveryPolicy.isBleReady(ConnectionState.CONNECTED_BLE_LAN));
        assertFalse(ConnectionRecoveryPolicy.isBleReady(ConnectionState.AUTHENTICATING));
    }

    @Test public void lanStaysUsableWhileBleRecoversInBackground() {
        assertTrue(ConnectionRecoveryPolicy.mayReuseLan(
                ConnectionState.CONNECTED_LAN, true, false));
        assertFalse(ConnectionRecoveryPolicy.mayReuseLan(
                ConnectionState.CONNECTED_LAN, true, true));
        assertFalse(ConnectionRecoveryPolicy.shouldExposeBackoff(true));
        assertTrue(ConnectionRecoveryPolicy.shouldExposeBackoff(false));
    }
}
