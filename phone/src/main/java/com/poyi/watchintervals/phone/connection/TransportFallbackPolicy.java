package com.poyi.watchintervals.phone.connection;

/** Retry policy for a read-only bulk request when the cached LAN endpoint goes stale. */
final class TransportFallbackPolicy {
    private TransportFallbackPolicy() {}

    static boolean shouldRetryOnBle(String method, TransportType selected,
            boolean bleAvailable) {
        return bleAvailable && selected == TransportType.LAN
                && "GET".equalsIgnoreCase(method);
    }

    static boolean isBleSessionReady(ConnectionState state) {
        return state == ConnectionState.CONNECTED_BLE
                || state == ConnectionState.CONNECTED_BLE_LAN
                || state == ConnectionState.DEGRADED_BLE;
    }

    static long remainingTtl(long expiresAt, long now) {
        return expiresAt <= 0L ? 0L : Math.max(0L, expiresAt - now);
    }
}
