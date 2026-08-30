package com.poyi.watchintervals;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * This week's training volume — the figure a runner opens the app to check between workouts.
 * Pure Java so the JVM suite covers the week-boundary arithmetic.
 */
final class WeeklyStats {
    static final long MIN_SENSORLESS_SESSION_MILLIS = 2 * 60_000L;
    final double meters;
    final long activeMillis;
    final int sessions;

    private WeeklyStats(double meters, long activeMillis, int sessions) {
        this.meters = meters;
        this.activeMillis = activeMillis;
        this.sessions = sessions;
    }

    static WeeklyStats of(List<WorkoutRecord> records, long now) {
        return of(records, now, TimeZone.getDefault());
    }

    static WeeklyStats of(List<WorkoutRecord> records, long now, TimeZone zone) {
        long weekStart = weekStartMillis(now, zone);
        double meters = 0;
        long active = 0;
        int sessions = 0;
        for (WorkoutRecord record : records) {
            if (record.startedAt < weekStart || record.startedAt > now) continue;
            // Sensor permission denial and indoor time targets can produce a legitimate session
            // with no distance or steps. Keep it once a stage completed or two active minutes
            // elapsed, while still filtering the short accidental starts this rule was built for.
            boolean sensorless = record.distanceMeters <= 0 && record.steps <= 0;
            boolean completedStage = record.stageResults != null && record.stageResults.length() > 0;
            if (sensorless && !completedStage
                    && record.durationMs < MIN_SENSORLESS_SESSION_MILLIS) continue;
            meters += record.distanceMeters;
            active += record.durationMs;
            sessions++;
        }
        return new WeeklyStats(meters, active, sessions);
    }

    /** Monday 00:00 of the week containing {@code now} — the mainland week convention. */
    static long weekStartMillis(long now, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(now);
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() > now) calendar.add(Calendar.DAY_OF_YEAR, -7);
        return calendar.getTimeInMillis();
    }
}
