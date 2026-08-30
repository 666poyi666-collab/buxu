package com.poyi.watchintervals.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Validated chronological projection of the HealthKit sleep stage samples. */
public final class PhoneSleepTimeline {
    public static final int UNKNOWN = 0;
    public static final int DEEP = 1;
    public static final int LIGHT = 2;
    public static final int REM = 3;
    public static final int AWAKE = 4;

    public final long startTime;
    public final long endTime;
    public final List<Segment> segments;
    public final int discardedStageCount;

    private PhoneSleepTimeline(long startTime, long endTime, List<Segment> segments,
            int discardedStageCount) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.segments = Collections.unmodifiableList(segments);
        this.discardedStageCount = discardedStageCount;
    }

    public static PhoneSleepTimeline from(JSONObject record) {
        ArrayList<Segment> values = new ArrayList<>();
        int discarded = 0;
        JSONArray sessions = record == null ? null : record.optJSONArray("sessions");
        if (sessions != null) {
            for (int sessionIndex = 0; sessionIndex < sessions.length(); sessionIndex++) {
                JSONObject session = sessions.optJSONObject(sessionIndex);
                JSONArray stages = session == null ? null : session.optJSONArray("stages");
                if (stages == null) continue;
                for (int stageIndex = 0; stageIndex < stages.length(); stageIndex++) {
                    JSONObject stage = stages.optJSONObject(stageIndex);
                    long start = positiveLong(stage, "startTime");
                    long end = positiveLong(stage, "endTime");
                    if (start <= 0L || end <= start) {
                        discarded++;
                        continue;
                    }
                    int rawType = intValue(stage, "type");
                    int type = rawType >= DEEP && rawType <= AWAKE ? rawType : UNKNOWN;
                    values.add(new Segment(start, end, type, rawType, sessionIndex));
                }
            }
        }
        values.sort(Comparator.comparingLong(value -> value.startTime));
        ArrayList<Segment> merged = mergeAdjacent(values);
        long start = merged.isEmpty() ? 0L : merged.get(0).startTime;
        long end = 0L;
        for (Segment value : merged) end = Math.max(end, value.endTime);
        return new PhoneSleepTimeline(start, end, merged, discarded);
    }

    public boolean available() {
        return !segments.isEmpty() && endTime > startTime;
    }

    public long durationMinutes(int type) {
        long millis = 0L;
        for (Segment segment : segments) if (segment.type == type) {
            millis += Math.max(0L, segment.endTime - segment.startTime);
        }
        return Math.round(millis / 60_000d);
    }

    public int unknownCount() {
        int count = 0;
        for (Segment segment : segments) if (segment.type == UNKNOWN) count++;
        return count;
    }

    public static String typeName(int type, int rawType) {
        if (type == DEEP) return "深睡";
        if (type == LIGHT) return "浅睡";
        if (type == REM) return "REM";
        if (type == AWAKE) return "清醒";
        return rawType == 0 ? "未知阶段" : "系统阶段 " + rawType;
    }

    private static ArrayList<Segment> mergeAdjacent(ArrayList<Segment> source) {
        ArrayList<Segment> result = new ArrayList<>();
        for (Segment next : source) {
            if (!result.isEmpty()) {
                Segment previous = result.get(result.size() - 1);
                if (previous.type == next.type && previous.rawType == next.rawType
                        && previous.sessionIndex == next.sessionIndex
                        && next.startTime <= previous.endTime + 60_000L) {
                    result.set(result.size() - 1, new Segment(previous.startTime,
                            Math.max(previous.endTime, next.endTime), previous.type,
                            previous.rawType, previous.sessionIndex));
                    continue;
                }
            }
            result.add(next);
        }
        return result;
    }

    private static int intValue(JSONObject value, String key) {
        if (value == null) return 0;
        Object raw = value.opt(key);
        if (raw instanceof Number) return ((Number) raw).intValue();
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (Exception invalid) { return 0; }
    }

    private static long positiveLong(JSONObject value, String key) {
        if (value == null) return 0L;
        try { return Math.max(0L, value.getLong(key)); }
        catch (Exception invalid) { return 0L; }
    }

    public static final class Segment {
        public final long startTime;
        public final long endTime;
        public final int type;
        public final int rawType;
        public final int sessionIndex;

        Segment(long startTime, long endTime, int type, int rawType, int sessionIndex) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.type = type;
            this.rawType = rawType;
            this.sessionIndex = sessionIndex;
        }
    }
}
