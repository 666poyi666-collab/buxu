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
    public static final class SessionItem {
        public final int index;
        public final long startTime;
        public final long endTime;
        public final long durationMinutes;
        public final long deepMinutes;
        public final long lightMinutes;
        public final long remMinutes;
        public final long awakeMinutes;

        public SessionItem(int index, long startTime, long endTime, long durationMinutes,
                           long deepMinutes, long lightMinutes, long remMinutes, long awakeMinutes) {
            this.index = index;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationMinutes = durationMinutes;
            this.deepMinutes = deepMinutes;
            this.lightMinutes = lightMinutes;
            this.remMinutes = remMinutes;
            this.awakeMinutes = awakeMinutes;
        }
    }

    public final java.util.List<SessionItem> sessions;
    public final long bedtime;
    public final long wakeTime;
    public final int heartRateMinBpm;
    public final int heartRateMaxBpm;
    public final double breathRateMinPerMinute;
    public final double breathRateMaxPerMinute;


    private PhoneSleepOverview(long timestamp, long totalDurationMinutes,
            boolean durationAvailable, int sleepScore, boolean scoreAvailable,
            int spo2AveragePercent, boolean spo2Available, int heartRateBenchmarkBpm,
            boolean heartRateAvailable, double breathRateBenchmarkPerMinute,
            boolean breathRateAvailable, long deepMinutes, long lightMinutes,
            long remMinutes, long awakeMinutes, boolean deepAvailable,
            boolean lightAvailable, boolean remAvailable, boolean awakeAvailable,
            boolean stageBreakdownAvailable,
            int sessionCount, int rawStageCount,
            java.util.List<SessionItem> sessions, long bedtime, long wakeTime,
            int heartRateMinBpm, int heartRateMaxBpm,
            double breathRateMinPerMinute, double breathRateMaxPerMinute) {
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
        this.sessions = sessions != null ? java.util.Collections.unmodifiableList(sessions) : java.util.Collections.emptyList();
        this.bedtime = bedtime;
        this.wakeTime = wakeTime;
        this.heartRateMinBpm = heartRateMinBpm;
        this.heartRateMaxBpm = heartRateMaxBpm;
        this.breathRateMinPerMinute = breathRateMinPerMinute;
        this.breathRateMaxPerMinute = breathRateMaxPerMinute;
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
        java.util.ArrayList<SessionItem> sessionItems = new java.util.ArrayList<>();
        long bedtime = 0L;
        long wakeTime = 0L;
        for (int index = 0; index < sessionCount; index++) {
            JSONObject session = sessions.optJSONObject(index);
            if (session == null) continue;
            long start = positiveLong(session, "startTime");
            long end = positiveLong(session, "endTime");
            if (start > 0L && (earliest <= 0L || start < earliest)) earliest = start;
            if (start > 0L && (bedtime <= 0L || start < bedtime)) bedtime = start;
            if (end > 0L && end > wakeTime) wakeTime = end;
            long sDuration = nonNegativeLong(session, "sleepDurationMinutes");
            long sDeep = nonNegativeLong(session, "deepDurationMinutes");
            long sLight = nonNegativeLong(session, "lightDurationMinutes");
            long sRem = nonNegativeLong(session, "remDurationMinutes");
            long sAwake = nonNegativeLong(session, "awakeDurationMinutes");
            if (hasNumber(session, "sleepDurationMinutes")) {
                sessionDurationPresent = true;
                sessionDuration += sDuration;
            }
            if (hasNumber(session, "deepDurationMinutes")) {
                deepPresent = true;
                deep += sDeep;
            }
            if (hasNumber(session, "lightDurationMinutes")) {
                lightPresent = true;
                light += sLight;
            }
            if (hasNumber(session, "remDurationMinutes")) {
                remPresent = true;
                rem += sRem;
            }
            if (hasNumber(session, "awakeDurationMinutes")) {
                awakePresent = true;
                awake += sAwake;
            }
            JSONArray stages = session.optJSONArray("stages");
            stageCount += stages == null ? 0 : stages.length();
            sessionItems.add(new SessionItem(index + 1, start, end, sDuration, sDeep, sLight, sRem, sAwake));
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
        JSONObject hrRange = record == null ? null : record.optJSONObject("heartRateRangeBpm");
        int hrMin = hrRange != null ? nonNegativeInt(hrRange, "minimum") : 0;
        int hrMax = hrRange != null ? nonNegativeInt(hrRange, "maximum") : 0;
        JSONObject brRange = record == null ? null : record.optJSONObject("breathRateRangePerMinute");
        double brMin = brRange != null ? nonNegativeDouble(brRange, "minimum") : 0d;
        double brMax = brRange != null ? nonNegativeDouble(brRange, "maximum") : 0d;

        return new PhoneSleepOverview(earliest, duration, durationAvailable,
                score, score > 0, spo2, spo2 > 0, heartRate, heartRate > 0,
                breathRate, breathRate > 0d, deep, light, rem, awake, deepPresent,
                lightPresent, remPresent, awakePresent, stageBreakdown,
                sessionCount, stageCount, sessionItems, bedtime, wakeTime,
                hrMin, hrMax, brMin, brMax);
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
