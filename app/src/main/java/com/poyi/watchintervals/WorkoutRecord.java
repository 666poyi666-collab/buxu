package com.poyi.watchintervals;

import android.location.Location;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

/** Versioned workout record. Old minimal JSON remains readable. */
final class WorkoutRecord {
    static final int SCHEMA_VERSION = 3;
    String id, plan, planName = "", planGroup = "", planRequirement = "";
    long startedAt, endedAt, durationMs;
    long pausedDurationMs, planCompletedActiveMs, planCompletedWallTime;
    double distanceMeters;
    double planDistanceMeters, freeRecordingDistanceMeters, maxSmoothedSpeedMps;
    int steps, averageHeartRate, calories;
    boolean synthetic;
    JSONArray stepTimeline = new JSONArray();
    int routePointCount;
    boolean routeTruncated;
    JSONObject distanceBySourceMeters = new JSONObject();
    JSONObject routePointCountBySource = new JSONObject();
    JSONArray sourceTransitions = new JSONArray();
    JSONObject locationAccuracySummary = new JSONObject();
    final ArrayList<Location> route = new ArrayList<>();
    final ArrayList<Long> heartTimes = new ArrayList<>();
    final ArrayList<Integer> heartValues = new ArrayList<>();
    JSONArray stageResults = new JSONArray();

    JSONObject toSummaryJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("schemaVersion", SCHEMA_VERSION).put("id", id).put("startedAt", startedAt).put("endedAt", endedAt)
                .put("durationMs", durationMs).put("distanceMeters", finite(distanceMeters)).put("steps", steps)
                .put("calories", Math.max(0, calories)).put("synthetic", synthetic)
                .put("stepTimeline", stepTimeline == null ? new JSONArray() : stepTimeline)
                .put("averageHeartRate", averageHeartRate).put("plan", plan == null ? "" : plan)
                .put("planName", planName).put("planGroup", planGroup).put("planRequirement", planRequirement)
                .put("pausedDurationMs", pausedDurationMs).put("elapsedDurationMs", Math.max(durationMs, endedAt - startedAt))
                .put("planCompletedActiveMs", planCompletedActiveMs).put("planCompletedWallTime", planCompletedWallTime)
                .put("freeRecordingActiveMs", planCompletedActiveMs > 0 ? Math.max(0, durationMs - planCompletedActiveMs) : 0)
                .put("planDistanceMeters", finite(planDistanceMeters)).put("freeRecordingDistanceMeters", finite(freeRecordingDistanceMeters))
                .put("maxSmoothedSpeedMps", finite(maxSmoothedSpeedMps)).put("routePointCount", Math.max(routePointCount, route.size()))
                .put("distanceBySourceMeters", distanceBySourceMeters).put("routePointCountBySource", routePointCountBySource)
                .put("sourceTransitions", sourceTransitions).put("locationAccuracySummary", locationAccuracySummary)
                .put("stageResults", stageResults == null ? new JSONArray() : stageResults);
        addDerived(json);
        return json;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = toSummaryJson();
        JSONArray points = new JSONArray();
        for (Location location : route) {
            JSONObject point = new JSONObject().put("latitude", location.getLatitude()).put("longitude", location.getLongitude())
                    .put("time", location.getTime()).put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
            if (location.hasAltitude()) point.put("altitude", location.getAltitude());
            if (location.hasSpeed()) point.put("speed", location.getSpeed());
            points.put(point);
        }
        JSONArray hearts = new JSONArray(); for (int i = 0; i < Math.min(heartTimes.size(), heartValues.size()); i++) hearts.put(new JSONArray().put(heartTimes.get(i)).put(heartValues.get(i)));
        json.put("route", points).put("heartRateSamples", hearts)
                .put("routePointCount", Math.max(routePointCount, route.size()))
                .put("routeTruncated", routeTruncated);
        return json;
    }

    private void addDerived(JSONObject json) throws JSONException {
        if (distanceMeters > 0 && durationMs > 0) json.put("averagePaceSecondsPerKm", Math.round(durationMs / 1000d * 1000d / distanceMeters));
        if (steps > 0 && durationMs >= 30_000) json.put("averageCadenceSpm", Math.round(steps * 60_000d / durationMs));
        double gain = 0; Location previous = null; for (Location point : route) { if (previous != null && point.hasAltitude() && previous.hasAltitude()) { double delta = point.getAltitude() - previous.getAltitude(); if (delta > 0.8 && delta < 50) gain += delta; } previous = point; }
        if (gain > 0) json.put("elevationGainMeters", Math.round(gain * 10d) / 10d);
        JSONArray splits = buildSplits(); if (splits.length() > 0) json.put("splits", splits);
        long best = bestPace(); if (best > 0) json.put("bestPaceSecondsPerKm", best);
        if (!heartValues.isEmpty()) json.put("heartRateRange", new JSONObject().put("min", java.util.Collections.min(heartValues)).put("max", java.util.Collections.max(heartValues)));
    }

    private JSONArray buildSplits() throws JSONException {
        JSONArray splits = new JSONArray(); if (route.size() < 2) return splits;
        double accumulated = 0, next = 1000; long splitStart = route.get(0).getTime(); Location previous = route.get(0);
        for (int i = 1; i < route.size(); i++) { Location point = route.get(i); double segment = previous.distanceTo(point); if (segment <= 0 || segment > 1000) { previous = point; continue; }
            accumulated += segment; while (accumulated >= next && point.getTime() > splitStart) { long duration = point.getTime() - splitStart; splits.put(new JSONObject().put("index", splits.length()+1).put("distanceMeters",1000).put("durationMs",duration).put("paceSecondsPerKm",Math.round(duration/1000d))); splitStart = point.getTime(); next += 1000; } previous = point; }
        double remainder = accumulated - splits.length()*1000d; if (remainder >= 100 && route.get(route.size()-1).getTime() > splitStart) { long duration=route.get(route.size()-1).getTime()-splitStart;splits.put(new JSONObject().put("index",splits.length()+1).put("distanceMeters",Math.round(remainder)).put("durationMs",duration).put("paceSecondsPerKm",Math.round(duration/1000d*1000d/remainder))); }
        return splits;
    }

    private long bestPace() { long best = Long.MAX_VALUE; for (int i = 1; i < route.size(); i++) { Location a=route.get(i-1),b=route.get(i);float meters=a.distanceTo(b);long dt=b.getTime()-a.getTime();if(meters>=5&&dt>0){long pace=Math.round(dt/1000d*1000d/meters);if(pace>=120&&pace<=1800)best=Math.min(best,pace);} } return best==Long.MAX_VALUE?0:best; }

    static WorkoutRecord fromJson(JSONObject json) throws JSONException {
        WorkoutRecord record = new WorkoutRecord();
        record.id = json.getString("id"); record.startedAt = json.optLong("startedAt"); record.endedAt = json.optLong("endedAt");
        record.durationMs = json.optLong("durationMs"); record.distanceMeters = finite(json.optDouble("distanceMeters", 0d)); record.steps = json.optInt("steps");
        record.averageHeartRate = json.optInt("averageHeartRate"); record.calories = Math.max(0, json.optInt("calories")); record.synthetic = json.optBoolean("synthetic");
        record.stepTimeline = json.optJSONArray("stepTimeline"); if (record.stepTimeline == null) record.stepTimeline = new JSONArray();
        record.plan = json.optString("plan"); record.planName=json.optString("planName");record.planGroup=json.optString("planGroup");record.planRequirement=json.optString("planRequirement");
        record.pausedDurationMs=json.optLong("pausedDurationMs");record.planCompletedActiveMs=json.optLong("planCompletedActiveMs");record.planCompletedWallTime=json.optLong("planCompletedWallTime");
        record.planDistanceMeters=finite(json.optDouble("planDistanceMeters",0d));record.freeRecordingDistanceMeters=finite(json.optDouble("freeRecordingDistanceMeters",0d));record.maxSmoothedSpeedMps=finite(json.optDouble("maxSmoothedSpeedMps",0d));
        record.routePointCount=json.optInt("routePointCount");record.routeTruncated=json.optBoolean("routeTruncated");
        if(json.optJSONObject("distanceBySourceMeters")!=null)record.distanceBySourceMeters=json.optJSONObject("distanceBySourceMeters");
        if(json.optJSONObject("routePointCountBySource")!=null)record.routePointCountBySource=json.optJSONObject("routePointCountBySource");
        if(json.optJSONArray("sourceTransitions")!=null)record.sourceTransitions=json.optJSONArray("sourceTransitions");
        if(json.optJSONObject("locationAccuracySummary")!=null)record.locationAccuracySummary=json.optJSONObject("locationAccuracySummary");
        JSONArray points = json.optJSONArray("route"); if (points != null) for (int index = 0; index < points.length(); index++) {
            Location location = new Location("history"); Object raw=points.opt(index);
            if(raw instanceof JSONArray){JSONArray point=(JSONArray)raw;if(point.length()<2)continue;location.setLatitude(point.optDouble(0));location.setLongitude(point.optDouble(1));}
            else if(raw instanceof JSONObject){JSONObject point=(JSONObject)raw;location.setLatitude(point.optDouble("latitude"));location.setLongitude(point.optDouble("longitude"));location.setTime(point.optLong("time"));if(point.has("altitude"))location.setAltitude(point.optDouble("altitude"));if(point.has("speed"))location.setSpeed((float)point.optDouble("speed"));if(point.has("accuracy"))location.setAccuracy((float)point.optDouble("accuracy"));}
            else continue; record.route.add(location);
        }
        JSONArray hearts=json.optJSONArray("heartRateSamples");if(hearts!=null)for(int i=0;i<hearts.length();i++){JSONArray sample=hearts.optJSONArray(i);if(sample!=null&&sample.length()>=2){record.heartTimes.add(sample.optLong(0));record.heartValues.add(sample.optInt(1));}}
        record.stageResults=json.optJSONArray("stageResults");if(record.stageResults==null)record.stageResults=new JSONArray();
        if(record.routePointCount<=0)record.routePointCount=record.route.size();return record;
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0d; }
}
