package com.poyi.watchintervals.phone.connection;

/** Pure connection recovery rules shared by the manager and JVM regressions. */
final class ConnectionRecoveryPolicy {
    private ConnectionRecoveryPolicy() {}

    static boolean isBleReady(ConnectionState state) {
        return state == ConnectionState.CONNECTED_BLE
                || state == ConnectionState.CONNECTED_BLE_LAN;
    }

    static boolean mayReuseLan(ConnectionState state, boolean lanVerified,
                               boolean forceBleRecovery) {
        return !forceBleRecovery && lanVerified && state == ConnectionState.CONNECTED_LAN;
    }

    static boolean shouldExposeBackoff(boolean lanVerified) {
        return !lanVerified;
    }
}
