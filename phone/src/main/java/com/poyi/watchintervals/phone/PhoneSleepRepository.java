package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable phone-side cache for sleep records already read from the paired watch. */
public final class PhoneSleepRepository {
    private static final String PREFS = "phone_sleep_cache";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RECORDS = 31;

    private PhoneSleepRepository() {}

    public static synchronized JSONObject load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SNAPSHOT, "");
        return decode(raw);
    }

    public static synchronized JSONObject mergeAndSave(Context context, JSONObject incoming,
            long cachedAt) throws Exception {
        JSONObject merged = merge(load(context), incoming, cachedAt);
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SNAPSHOT, merged.toString());
        if (!edit.commit()) throw new IllegalStateException("sleep cache write failed");
        return merged;
    }

    public static JSONObject merge(JSONObject cached, JSONObject incoming, long cachedAt)
            throws Exception {
        if (!isReady(incoming)) throw new IllegalArgumentException("sleep snapshot is not ready");
        LinkedHashMap<String, JSONObject> records = new LinkedHashMap<>();
        addRecords(records, incoming.optJSONArray("records"));
        if (isReady(cached)) addRecords(records, cached.optJSONArray("records"));
        ArrayList<JSONObject> sorted = new ArrayList<>(records.values());
        sorted.sort(Comparator.comparingLong(PhoneSleepRepository::recordTimestamp).reversed());
        JSONArray output = new JSONArray();
        int requestedDays = Math.max(incoming.optInt("requestedDays", 0),
                cached == null ? 0 : cached.optInt("requestedDays", 0));
        boolean incomingHasRecords = incoming.optJSONArray("records").length() > 0;
        boolean cachedHasRecords = cached != null && cached.optJSONArray("records") != null
                && cached.optJSONArray("records").length() > 0;
        boolean useIncomingMetadata = incomingHasRecords || !cachedHasRecords;
        boolean completeValue = useIncomingMetadata
                ? incoming.optBoolean("complete", false)
                : cached != null && cached.optBoolean("complete", false);
        boolean hasMoreValue = useIncomingMetadata
                ? incoming.optBoolean("hasMore", false)
                : cached != null && cached.optBoolean("hasMore", false);
        long coverageStart = Math.max(0L, useIncomingMetadata
                ? incoming.optLong("coverageStart")
                : cached == null ? 0L : cached.optLong("coverageStart"));
        long coverageEnd = Math.max(0L, useIncomingMetadata
                ? incoming.optLong("coverageEnd")
                : cached == null ? 0L : cached.optLong("coverageEnd"));
        boolean completeWindow = incomingHasRecords && completeValue;
        for (JSONObject record : sorted) {
            long timestamp = recordTimestamp(record);
            if (completeWindow && coverageStart > 0L && timestamp > 0L
                    && (timestamp < coverageStart
                    || (coverageEnd > coverageStart && timestamp > coverageEnd))) continue;
            output.put(record);
            if (output.length() >= MAX_RECORDS) break;
        }
        long dataCachedAt = incomingHasRecords || !cachedHasRecords ? cachedAt
                : cached.optLong("cachedAt", 0L);
        return new JSONObject().put("schemaVersion", SCHEMA_VERSION)
                .put("state", "ready").put("source", "system_healthkit")
                .put("requestedDays", requestedDays)
                .put("cachedAt", Math.max(0L, dataCachedAt))
                .put("lastCheckedAt", Math.max(0L, cachedAt))
                .put("sourceFetchedAt", useIncomingMetadata
                        ? incoming.optLong("fetchedAt", cachedAt)
                        : cached == null ? 0L : cached.optLong("sourceFetchedAt", 0L))
                .put("complete", completeValue)
                .put("hasMore", hasMoreValue)
                .put("coverageStart", coverageStart).put("coverageEnd", coverageEnd)
                .put("records", output);
    }

    public static JSONObject decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject source = new JSONObject(raw);
            if (!isReady(source)) return null;
            int version = source.optInt("schemaVersion", 0);
            if (version > SCHEMA_VERSION) return null;
            JSONArray records = source.optJSONArray("records");
            if (records == null) return null;
            LinkedHashMap<String, JSONObject> normalized = new LinkedHashMap<>();
            addRecords(normalized, records);
            ArrayList<JSONObject> sorted = new ArrayList<>(normalized.values());
            sorted.sort(Comparator.comparingLong(PhoneSleepRepository::recordTimestamp)
                    .reversed());
            JSONArray cleanRecords = new JSONArray();
            for (int index = 0; index < sorted.size() && index < MAX_RECORDS; index++) {
                cleanRecords.put(sorted.get(index));
            }
            // schemaVersion 0 is the legacy ready envelope used before the durable wrapper.
            long cachedAt = Math.max(0L, source.optLong("cachedAt",
                    source.optLong("fetchedAt", 0L)));
            return new JSONObject().put("schemaVersion", SCHEMA_VERSION)
                    .put("state", "ready")
                    .put("source", source.optString("source", "system_healthkit"))
                    .put("requestedDays", Math.max(0, source.optInt("requestedDays", 0)))
                    .put("cachedAt", cachedAt)
                    .put("lastCheckedAt", Math.max(0L, source.optLong("lastCheckedAt", cachedAt)))
                    .put("sourceFetchedAt", Math.max(0L,
                            source.optLong("sourceFetchedAt", source.optLong("fetchedAt", 0L))))
                    .put("complete", source.optBoolean("complete", false))
                    .put("hasMore", source.optBoolean("hasMore", false))
                    .put("coverageStart", Math.max(0L, source.optLong("coverageStart")))
                    .put("coverageEnd", Math.max(0L, source.optLong("coverageEnd")))
                    .put("records", cleanRecords);
        } catch (Exception corrupt) {
            return null;
        }
    }

    private static void addRecords(Map<String, JSONObject> output, JSONArray records)
            throws Exception {
        if (records == null) return;
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.optJSONObject(index);
            if (record == null) continue;
            JSONObject copy = new JSONObject(record.toString());
            String key = recordKey(copy);
            if (!output.containsKey(key)) output.put(key, copy);
        }
    }

    private static boolean isReady(JSONObject value) {
        return value != null && "ready".equals(value.optString("state"))
                && value.optJSONArray("records") != null;
    }

    private static String recordKey(JSONObject record) {
        long timestamp = Math.max(0L, record.optLong("timestamp"));
        if (timestamp <= 0L) timestamp = recordTimestamp(record);
        return timestamp > 0L ? "time:" + timestamp : "raw:" + record.toString();
    }

    private static long recordTimestamp(JSONObject record) {
        long timestamp = Math.max(0L, record.optLong("timestamp"));
        if (timestamp > 0L) return timestamp;
        JSONArray sessions = record.optJSONArray("sessions");
        if (sessions == null) return timestamp;
        for (int index = 0; index < sessions.length(); index++) {
            JSONObject session = sessions.optJSONObject(index);
            if (session == null) continue;
            long start = Math.max(0L, session.optLong("startTime"));
            if (start > 0L && (timestamp <= 0L || start < timestamp)) timestamp = start;
        }
        return timestamp;
    }
}
