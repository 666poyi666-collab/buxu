package com.poyi.watchintervals;

/** Pure timing and sensor cadence rules shared by the preparation UI and service. */
public final class WorkoutPreparationPolicy {
    public static final long COUNTDOWN_MILLIS = 3_000L;
    public static final long PREPARATION_LOCATION_INTERVAL_MILLIS = 1_000L;
    public static final long PREPARATION_LOCKED_LOCATION_INTERVAL_MILLIS = 5_000L;
    public static final long WORKOUT_LOCATION_INTERVAL_MILLIS = 2_000L;
    public static final long PAUSED_LOCATION_INTERVAL_MILLIS = 10_000L;
    public static final long SINGLE_FIX_RETRY_MILLIS = 15_000L;

    private WorkoutPreparationPolicy() {}

    public static long remainingMillis(long deadlineElapsed, long nowElapsed) {
        if (deadlineElapsed <= 0L) return 0L;
        return Math.max(0L, deadlineElapsed - nowElapsed);
    }

    /** Returns 3/2/1 for the visible beats and 0 after the hand-off point. */
    public static int countdownFrame(long deadlineElapsed, long nowElapsed) {
        long remaining = remainingMillis(deadlineElapsed, nowElapsed);
        if (remaining <= 0L) return 0;
        return (int) Math.min(3L, Math.max(1L, (remaining + 999L) / 1_000L));
    }

    public static long locationIntervalMillis(boolean running, boolean paused,
            boolean hasFreshFix) {
        if (running && paused) return PAUSED_LOCATION_INTERVAL_MILLIS;
        if (running) return WORKOUT_LOCATION_INTERVAL_MILLIS;
        return hasFreshFix ? PREPARATION_LOCKED_LOCATION_INTERVAL_MILLIS
                : PREPARATION_LOCATION_INTERVAL_MILLIS;
    }

    public static boolean shouldHoldWakeLock(boolean running) {
        return running;
    }

    public static boolean shouldRetrySingleFix(boolean active, boolean hasFix,
            boolean requestInFlight, long lastRequestElapsed, long nowElapsed) {
        return active && !hasFix && !requestInFlight
                && (lastRequestElapsed <= 0L
                || nowElapsed - lastRequestElapsed >= SINGLE_FIX_RETRY_MILLIS);
    }
}
