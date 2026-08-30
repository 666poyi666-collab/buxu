package com.poyi.watchintervals.phone;

import org.json.JSONArray;
import org.json.JSONObject;

/** Truth-preserving projection of one system sleep record for the phone overview. */
public final class PhoneSleepOverview {
    public final long timestamp;
    public final long totalDurationMinutes;
    public final boolean durationAvailable;
    public final int sleepScore;
    public final boolean scoreAvailable;
    public final int spo2AveragePercent;
    public final boolean spo2Available;
    public final int heartRateBenchmarkBpm;
    public final boolean heartRateAvailable;
    public final double breathRateBenchmarkPerMinute;
    public final boolean breathRateAvailable;
    public final long deepMinutes;
    public final boolean deepAvailable;
    public final long lightMinutes;
    public final boolean lightAvailable;
    public final long remMinutes;
    public final boolean remAvailable;
    public final long awakeMinutes;
    public final boolean awakeAvailable;
    public final boolean stageBreakdownAvailable;
    public final int sessionCount;
    public final int rawStageCount;

    private PhoneSleepOverview(long timestamp, long totalDurationMinutes,
            boolean durationAvailable, int sleepScore, boolean scoreAvailable,
            int spo2AveragePercent, boolean spo2Available, int heartRateBenchmarkBpm,
            boolean heartRateAvailable, double breathRateBenchmarkPerMinute,
            boolean breathRateAvailable, long deepMinutes, long lightMinutes,
            long remMinutes, long awakeMinutes, boolean deepAvailable,
            boolean lightAvailable, boolean remAvailable, boolean awakeAvailable,
            boolean stageBreakdownAvailable,
            int sessionCount, int rawStageCount) {
        this.timestamp = timestamp;
        this.totalDurationMinutes = totalDurationMinutes;
        this.durationAvailable = durationAvailable;
        this.sleepScore = sleepScore;
        this.scoreAvailable = scoreAvailable;
        this.spo2AveragePercent = spo2AveragePercent;
        this.spo2Available = spo2Available;
        this.heartRateBenchmarkBpm = heartRateBenchmarkBpm;
        this.heartRateAvailable = heartRateAvailable;
        this.breathRateBenchmarkPerMinute = breathRateBenchmarkPerMinute;
        this.breathRateAvailable = breathRateAvailable;
        this.deepMinutes = deepMinutes;
        this.lightMinutes = lightMinutes;
        this.remMinutes = remMinutes;
        this.awakeMinutes = awakeMinutes;
        this.deepAvailable = deepAvailable;
        this.lightAvailable = lightAvailable;
        this.remAvailable = remAvailable;
        this.awakeAvailable = awakeAvailable;
        this.stageBreakdownAvailable = stageBreakdownAvailable;
        this.sessionCount = sessionCount;
        this.rawStageCount = rawStageCount;
    }

    public static PhoneSleepOverview from(JSONObject record) {
        JSONArray sessions = record == null ? null : record.optJSONArray("sessions");
        int sessionCount = sessions == null ? 0 : sessions.length();
        long earliest = positiveLong(record, "timestamp");
        long sessionDuration = 0L;
        boolean sessionDurationPresent = false;
        long deep = 0L, light = 0L, rem = 0L, awake = 0L;
        boolean deepPresent = false, lightPresent = false, remPresent = false,
                awakePresent = false;
        int stageCount = 0;
        for (int index = 0; index < sessionCount; index++) {
            JSONObject session = sessions.optJSONObject(index);
            if (session == null) continue;
            long start = positiveLong(session, "startTime");
            if (start > 0L && (earliest <= 0L || start < earliest)) earliest = start;
            if (hasNumber(session, "sleepDurationMinutes")) {
                sessionDurationPresent = true;
                sessionDuration += nonNegativeLong(session, "sleepDurationMinutes");
            }
            if (hasNumber(session, "deepDurationMinutes")) {
                deepPresent = true;
                deep += nonNegativeLong(session, "deepDurationMinutes");
            }
            if (hasNumber(session, "lightDurationMinutes")) {
                lightPresent = true;
                light += nonNegativeLong(session, "lightDurationMinutes");
            }
            if (hasNumber(session, "remDurationMinutes")) {
                remPresent = true;
                rem += nonNegativeLong(session, "remDurationMinutes");
            }
            if (hasNumber(session, "awakeDurationMinutes")) {
                awakePresent = true;
                awake += nonNegativeLong(session, "awakeDurationMinutes");
            }
            JSONArray stages = session.optJSONArray("stages");
            stageCount += stages == null ? 0 : stages.length();
        }

        boolean recordDurationPresent = hasNumber(record, "totalDurationMinutes")
                && nonNegativeLong(record, "totalDurationMinutes") > 0L;
        long duration = recordDurationPresent
                ? nonNegativeLong(record, "totalDurationMinutes") : sessionDuration;
        boolean durationAvailable = recordDurationPresent
                || (sessionDurationPresent && sessionDuration > 0L);
        int score = nonNegativeInt(record, "sleepScore");
        int spo2 = nonNegativeInt(record, "spo2AveragePercent");
        int heartRate = nonNegativeInt(record, "heartRateBenchmarkBpm");
        double breathRate = nonNegativeDouble(record, "breathRateBenchmarkPerMinute");
        boolean stageBreakdown = deepPresent && lightPresent && remPresent && awakePresent
                && deep + light + rem + awake > 0L;
        return new PhoneSleepOverview(earliest, duration, durationAvailable,
                score, score > 0, spo2, spo2 > 0, heartRate, heartRate > 0,
                breathRate, breathRate > 0d, deep, light, rem, awake, deepPresent,
                lightPresent, remPresent, awakePresent, stageBreakdown,
                sessionCount, stageCount);
    }

    public long stageTotalMinutes() {
        return deepMinutes + lightMinutes + remMinutes + awakeMinutes;
    }

    public long[] stageMinutes() {
        return new long[]{deepMinutes, lightMinutes, remMinutes, awakeMinutes};
    }

    private static boolean hasNumber(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (raw instanceof Number) return true;
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return false;
        try {
            double parsed = Double.parseDouble(((String) raw).trim());
            return Double.isFinite(parsed);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static long positiveLong(JSONObject value, String key) {
        long result = nonNegativeLong(value, key);
        return result > 0L ? result : 0L;
    }

    private static long nonNegativeLong(JSONObject value, String key) {
        if (value == null) return 0L;
        try { return Math.max(0L, value.getLong(key)); }
        catch (Exception invalid) { return 0L; }
    }

    private static int nonNegativeInt(JSONObject value, String key) {
        return (int) Math.min(Integer.MAX_VALUE, nonNegativeLong(value, key));
    }

    private static double nonNegativeDouble(JSONObject value, String key) {
        if (value == null) return 0d;
        try {
            double result = value.getDouble(key);
            return Double.isFinite(result) ? Math.max(0d, result) : 0d;
        } catch (Exception invalid) { return 0d; }
    }
}
