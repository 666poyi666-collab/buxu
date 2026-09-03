package com.poyi.watchintervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class HistoryStoreSummaryTest {
    @Test public void cloudSummariesPreserveDerivedSplitsWithoutPrivateSamples() throws Exception {
        JSONObject summary = new JSONObject()
                .put("id", "synthetic-split")
                .put("distanceMeters", 1200)
                .put("steps", 10000)
                .put("calories", 500)
                .put("synthetic", true)
                .put("stepTimeline", new JSONArray()
                        .put(new JSONObject().put("elapsedMs", 600000).put("steps", 7000))
                        .put(new JSONObject().put("elapsedMs", 1200000).put("steps", 10000)))
                .put("splits", new JSONArray()
                        .put(new JSONObject().put("index", 1)
                                .put("distanceMeters", 1000)
                                .put("durationMs", 300_000)
                                .put("paceSecondsPerKm", 300)))
                .put("bestPaceSecondsPerKm", 292)
                .put("heartRateRange", new JSONObject().put("min", 128).put("max", 164))
                .put("route", new JSONArray().put(new JSONObject()
                        .put("latitude", 30).put("longitude", 120)))
                .put("heartRateSamples", new JSONArray().put(new JSONArray().put(1).put(150)))
                .put("coordinates", new JSONArray().put(30).put(120));

        JSONArray result = HistoryStore.summariesForSync(new JSONArray().put(summary));

        assertEquals(1, result.length());
        JSONObject copied = result.getJSONObject(0);
        assertEquals(1, copied.getJSONArray("splits").length());
        assertEquals(300, copied.getJSONArray("splits").getJSONObject(0)
                .getInt("paceSecondsPerKm"));
        assertEquals(292, copied.getInt("bestPaceSecondsPerKm"));
        assertTrue(copied.has("heartRateRange"));
        assertEquals(10000, copied.getInt("steps"));
        assertEquals(500, copied.getInt("calories"));
        assertTrue(copied.getBoolean("synthetic"));
        assertEquals(2, copied.getJSONArray("stepTimeline").length());
        assertFalse(copied.has("route"));
        assertFalse(copied.has("heartRateSamples"));
        assertFalse(copied.has("coordinates"));
    }
}
