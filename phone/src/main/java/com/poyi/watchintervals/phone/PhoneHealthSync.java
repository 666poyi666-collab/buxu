package com.poyi.watchintervals.phone;

import com.poyi.watchintervals.phone.connection.WatchConnectionManager;

import org.json.JSONObject;

/** Fetches the health summary from the paired watch over the authenticated transport. */
public final class PhoneHealthSync {
    private PhoneHealthSync() {}

    public static JSONObject fetchRecent(WatchConnectionManager manager, int requestedDays)
            throws Exception {
        int days = Math.max(1, Math.min(31, requestedDays));
        JSONObject value = new JSONObject(manager.requestBlocking(
                "GET", "/v1/health?days=" + days, "", 25_000L));
        if ("ready".equals(value.optString("state"))) {
            PhoneHealthRepository.save(manager.context(), value);
        }
        return value;
    }
}
