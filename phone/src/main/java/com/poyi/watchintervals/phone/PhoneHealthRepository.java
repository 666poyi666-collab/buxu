package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Durable phone-side cache for the health summary already read from the paired watch. */
public final class PhoneHealthRepository {
    private static final String PREFS = "phone_health_cache";
    private static final String KEY_SNAPSHOT = "snapshot";

    private PhoneHealthRepository() {}

    public static synchronized JSONObject load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SNAPSHOT, "");
        if (raw == null || raw.isEmpty()) return empty();
        try { return new JSONObject(raw); }
        catch (Exception corrupted) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("corrupt_snapshot_backup_" + System.currentTimeMillis(), raw).apply();
            return empty();
        }
    }

    private static JSONObject empty() {
        try {
            return new JSONObject().put("state", "empty").put("source", "system_healthkit")
                    .put("fetchedAt", 0L).put("records", new org.json.JSONArray());
        } catch (Exception ignored) { return new JSONObject(); }
    }

    public static synchronized JSONObject save(Context context, JSONObject snapshot) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SNAPSHOT, snapshot.toString());
        if (!edit.commit()) throw new IllegalStateException("health_cache_commit_failed");
        return snapshot;
    }
}
