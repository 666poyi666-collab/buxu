package com.poyi.watchintervals.phone;

import android.content.Context;
import com.poyi.watchintervals.phone.connection.ConnectionState;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;

/** Keeps the phone's authoritative plan library projected onto the paired watch. */
public final class PhonePlanProjectionSync {
    private PhonePlanProjectionSync() {}

    public static boolean drainOnce(Context context) {
        if (PhoneSyncOutbox.size(context) == 0) return true;
        try {
            org.json.JSONObject result = PhoneSyncOutbox.drain(
                    context, WatchConnectionManager.get(context));
            return "synced".equals(result.optString("state"))
                    && result.optInt("pendingOperations", 1) == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    public static boolean shouldAttempt(ConnectionState state, int pendingOperations,
                                 boolean drainInFlight) {
        if (pendingOperations <= 0 || drainInFlight || state == null) return false;
        return state == ConnectionState.CONNECTED_BLE
                || state == ConnectionState.CONNECTED_BLE_LAN
                || state == ConnectionState.CONNECTED_LAN
                || state == ConnectionState.DEGRADED_BLE;
    }
}
