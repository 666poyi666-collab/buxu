package com.poyi.watchintervals;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.TimeZone;
import org.junit.Test;

public class WeeklyStatsTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    /** 2026-07-26 20:00 +0800, a Sunday. The containing week starts Monday 2026-07-20 00:00. */
    private static final long SUNDAY_EVENING = 1785067200000L;
    private static final long WEEK_START = 1784476800000L;

    private WorkoutRecord record(long startedAt, double meters, int steps, long durationMs) {
        WorkoutRecord record = new WorkoutRecord();
        record.id = "r" + startedAt;
        record.startedAt = startedAt;
        record.distanceMeters = meters;
        record.steps = steps;
        record.durationMs = durationMs;
        return record;
    }

    @Test public void weekStartsOnMondayMidnight() {
        assertEquals(WEEK_START, WeeklyStats.weekStartMillis(SUNDAY_EVENING, SHANGHAI));
        // A Monday morning belongs to its own week, not the previous one.
        long mondayMorning = WEEK_START + 8 * 3600_000L;
        assertEquals(WEEK_START, WeeklyStats.weekStartMillis(mondayMorning, SHANGHAI));
    }

    @Test public void sumsOnlyThisWeeksSessions() {
        ArrayList<WorkoutRecord> records = new ArrayList<>();
        records.add(record(WEEK_START + 3600_000L, 5000, 6000, 1800_000L));
        records.add(record(SUNDAY_EVENING - 3600_000L, 10200, 12480, 4532_000L));
        records.add(record(WEEK_START - 1, 8000, 9000, 3000_000L));          // last week
        records.add(record(SUNDAY_EVENING + 3600_000L, 4000, 5000, 1500_000L)); // clock skew future
        WeeklyStats stats = WeeklyStats.of(records, SUNDAY_EVENING, SHANGHAI);
        assertEquals(15200, stats.meters, 0.01);
        assertEquals(6332_000L, stats.activeMillis);
        assertEquals(2, stats.sessions);
    }

    @Test public void ignoresAllZeroRecords() {
        ArrayList<WorkoutRecord> records = new ArrayList<>();
        records.add(record(WEEK_START + 3600_000L, 0, 0, 90_000L));
        records.add(record(WEEK_START + 7200_000L, 0, 4200, 1200_000L)); // indoor steps count
        WeeklyStats stats = WeeklyStats.of(records, SUNDAY_EVENING, SHANGHAI);
        assertEquals(1, stats.sessions);
        assertEquals(1200_000L, stats.activeMillis);
    }

    @Test public void keepsMeaningfulSensorlessTimeSessions() {
        ArrayList<WorkoutRecord> records = new ArrayList<>();
        records.add(record(WEEK_START + 3600_000L, 0, 0,
                WeeklyStats.MIN_SENSORLESS_SESSION_MILLIS));
        WorkoutRecord completed = record(WEEK_START + 7200_000L, 0, 0, 60_000L);
        completed.stageResults.put(new org.json.JSONObject());
        records.add(completed);

        WeeklyStats stats = WeeklyStats.of(records, SUNDAY_EVENING, SHANGHAI);
        assertEquals(2, stats.sessions);
        assertEquals(180_000L, stats.activeMillis);
        assertEquals(0, stats.meters, 0.01);
    }

    @Test public void emptyHistoryIsAnEmptyWeek() {
        WeeklyStats stats = WeeklyStats.of(new ArrayList<>(), SUNDAY_EVENING, SHANGHAI);
        assertEquals(0, stats.sessions);
        assertEquals(0, stats.meters, 0.01);
        assertEquals(0, stats.activeMillis);
    }
}
