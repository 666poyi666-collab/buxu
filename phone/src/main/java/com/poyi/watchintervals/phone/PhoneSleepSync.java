package com.poyi.watchintervals.phone;

import com.poyi.watchintervals.phone.connection.WatchConnectionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Fetches the 31-day sleep window in bounded pages so BLE never carries one giant response. */
final class PhoneSleepSync {
    static final int PAGE_DAYS = 7;
    private static final Object FETCH_LOCK = new Object();
    private static CompletableFuture<JSONObject> activeFetch;

    private PhoneSleepSync() {}

    static JSONObject fetchRecent(WatchConnectionManager manager, int requestedDays)
            throws Exception {
        return runSingleFlight(() -> fetchPages(manager, requestedDays));
    }

    private static JSONObject fetchPages(WatchConnectionManager manager, int requestedDays)
            throws Exception {
        int days = Math.max(1, Math.min(31, requestedDays));
        JSONArray pages = new JSONArray();
        for (int offset = 0; offset < days; offset += PAGE_DAYS) {
            int pageDays = Math.min(PAGE_DAYS, days - offset);
            String path = "/v1/sleep?days=" + pageDays + "&offsetDays=" + offset;
            JSONObject page = new JSONObject(manager.requestBlocking("GET", path, "", 25_000L));
            if (!"ready".equals(page.optString("state"))) return page;
            pages.put(page);
        }
        return mergePages(pages, days, System.currentTimeMillis());
    }

    static JSONObject runSingleFlight(FetchOperation operation) throws Exception {
        CompletableFuture<JSONObject> shared;
        boolean owner = false;
        synchronized (FETCH_LOCK) {
            shared = activeFetch;
            if (shared == null) {
                shared = new CompletableFuture<>();
                activeFetch = shared;
                owner = true;
            }
        }
        if (owner) {
            try {
                JSONObject result = operation.fetch();
                shared.complete(new JSONObject(result.toString()));
            } catch (Throwable error) {
                shared.completeExceptionally(error);
            } finally {
                synchronized (FETCH_LOCK) {
                    if (activeFetch == shared) activeFetch = null;
                }
            }
        }
        try {
            return new JSONObject(shared.get().toString());
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    interface FetchOperation {
        JSONObject fetch() throws Exception;
    }

    static JSONObject mergePages(JSONArray pages, int requestedDays, long fetchedAt)
            throws Exception {
        Map<String, JSONObject> records = new LinkedHashMap<>();
        boolean complete = pages != null && pages.length() > 0;
        long coverageStart = 0L;
        long coverageEnd = 0L;
        if (pages != null) for (int pageIndex = 0; pageIndex < pages.length(); pageIndex++) {
            JSONObject page = pages.optJSONObject(pageIndex);
            if (page == null || !"ready".equals(page.optString("state"))) {
                complete = false;
                continue;
            }
            complete &= page.optBoolean("complete", !page.optBoolean("hasMore", false));
            long start = Math.max(0L, page.optLong("coverageStart"));
            long end = Math.max(0L, page.optLong("coverageEnd"));
            if (start > 0L && (coverageStart <= 0L || start < coverageStart)) coverageStart = start;
            if (end > coverageEnd) coverageEnd = end;
            JSONArray values = page.optJSONArray("records");
            if (values == null) continue;
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                JSONObject copy = new JSONObject(value.toString());
                records.put(recordKey(copy), copy);
            }
        }
        ArrayList<JSONObject> sorted = new ArrayList<>(records.values());
        sorted.sort(Comparator.comparingLong(PhoneSleepSync::recordTimestamp).reversed());
        JSONArray output = new JSONArray();
        for (JSONObject value : sorted) output.put(value);
        return new JSONObject().put("state", "ready").put("source", "system_healthkit")
                .put("requestedDays", Math.max(1, Math.min(31, requestedDays)))
                .put("fetchedAt", Math.max(0L, fetchedAt)).put("complete", complete)
                .put("hasMore", !complete).put("coverageStart", coverageStart)
                .put("coverageEnd", coverageEnd).put("recordCount", output.length())
                .put("records", output);
    }

    private static String recordKey(JSONObject record) {
        long timestamp = Math.max(0L, record.optLong("timestamp"));
        return timestamp > 0L ? "time:" + timestamp : "raw:" + record.toString();
    }

    private static long recordTimestamp(JSONObject record) {
        return Math.max(0L, record.optLong("timestamp"));
    }
}
