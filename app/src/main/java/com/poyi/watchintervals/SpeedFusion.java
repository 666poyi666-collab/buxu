package com.poyi.watchintervals;

/**
 * Produces the speed and pace shown during training.
 *
 * <p>Distance-differenced speed alone reads badly on a watch: a 10 s window lags every change of
 * effort, and GPS jitter makes the number twitch while running at a constant pace. The GNSS chip
 * already reports a Doppler-derived instantaneous speed on every fix, which is both faster to
 * react and far steadier at running speeds, so this class prefers that native signal and keeps the
 * windowed distance figure as the fallback for when the chip stops reporting it.
 *
 * <p>Deliberately free of Android types so the fusion rules stay unit-testable.
 */
final class SpeedFusion {
    /** Doppler samples older than this no longer describe the current effort. */
    static final long GNSS_FRESH_MILLIS = 3_000L;
    /** OWW221 reports ~0.3-0.9 m/s accuracy with a fix; anything worse is chip noise, not running. */
    static final double MAX_GNSS_SPEED_ACCURACY_MPS = 2.5d;
    /** Above this a "run" is a vehicle or a GNSS glitch. */
    static final double MAX_PLAUSIBLE_SPEED_MPS = 12.5d;
    /** Below this the wearer is standing; showing 0.4 km/h reads as a broken instrument. */
    static final double MOVING_THRESHOLD_MPS = 0.5d;
    /** Exponential smoothing constant. ~4 s feels responsive without visible flicker. */
    static final long SMOOTHING_TAU_MILLIS = 3_500L;
    /** Hold smoothed speed across short dropouts (<= 2.5s) to avoid instant NaN collapse. */
    static final long DROPOUT_HOLD_MILLIS = 2_500L;

    enum Source { GNSS_DOPPLER, DISTANCE_WINDOW, NONE }

    private double smoothed = Double.NaN;
    private long smoothedAt;
    /** Explicit flag: a zero timestamp is a valid clock value, so it cannot mark "unseeded". */
    private boolean seeded;

    private double gnssSpeed = Double.NaN;
    private long gnssAt;

    private double windowSpeed = Double.NaN;
    private boolean windowEstimated;
    private long windowAt;

    private Source source = Source.NONE;

    /**
     * Feeds one native GNSS fix.
     *
     * @param speedAccuracyMps the chip's own accuracy estimate, or a negative value when the
     *                         platform does not supply one (older fixes still carry a usable speed).
     */
    void addGnssSpeed(long at, double speedMps, double speedAccuracyMps) {
        if (!Double.isFinite(speedMps) || speedMps < 0d || speedMps > MAX_PLAUSIBLE_SPEED_MPS) return;
        if (speedAccuracyMps >= 0d && speedAccuracyMps > MAX_GNSS_SPEED_ACCURACY_MPS) return;
        gnssSpeed = speedMps;
        gnssAt = at;
    }

    /** Feeds the distance-window speed already maintained by {@link WorkoutMetricsAccumulator}. */
    void addWindowSpeed(long at, double speedMps, boolean estimated) {
        if (!Double.isFinite(speedMps) || speedMps < 0d || speedMps > MAX_PLAUSIBLE_SPEED_MPS) {
            windowSpeed = Double.NaN;
            return;
        }
        windowSpeed = speedMps;
        windowEstimated = estimated;
        windowAt = at;
    }

    /**
     * Smoothed speed in m/s, or {@code NaN} when no source can describe the current moment.
     * Call once per UI tick; smoothing advances with the supplied clock.
     */
    double speedMps(long now) {
        double raw = rawSpeed(now);
        if (Double.isNaN(raw)) {
            if (seeded && now - smoothedAt <= DROPOUT_HOLD_MILLIS) {
                return smoothed < MOVING_THRESHOLD_MPS ? 0d : smoothed;
            }
            smoothed = Double.NaN;
            smoothedAt = now;
            seeded = false;
            return Double.NaN;
        }
        if (!seeded || now <= smoothedAt) {
            smoothed = raw;
            smoothedAt = now;
            seeded = true;
        } else {
            // Time-based EMA so an irregular fix cadence cannot change the smoothing strength.
            double alpha = 1d - Math.exp(-(double)(now - smoothedAt) / SMOOTHING_TAU_MILLIS);
            smoothed += alpha * (raw - smoothed);
            smoothedAt = now;
        }
        return smoothed < MOVING_THRESHOLD_MPS ? 0d : smoothed;
    }

    private double rawSpeed(long now) {
        if (Double.isFinite(gnssSpeed) && now - gnssAt <= GNSS_FRESH_MILLIS) {
            source = Source.GNSS_DOPPLER;
            return gnssSpeed;
        }
        if (Double.isFinite(windowSpeed) && now - windowAt <= WorkoutMetricsAccumulator.STALE_MILLIS) {
            source = Source.DISTANCE_WINDOW;
            return windowSpeed;
        }
        source = Source.NONE;
        return Double.NaN;
    }

    Source source() { return source; }

    /** True when the shown figure comes from step estimation rather than a satellite fix. */
    boolean estimated() { return source == Source.DISTANCE_WINDOW && windowEstimated; }

    /** Seconds per kilometre, or {@code NaN} while stopped or unmeasured. */
    double paceSecondsPerKm(long now) {
        double speed = speedMps(now);
        if (!Double.isFinite(speed) || speed < MOVING_THRESHOLD_MPS) return Double.NaN;
        return 1000d / speed;
    }

    void reset() {
        smoothed = Double.NaN;
        smoothedAt = 0L;
        seeded = false;
        gnssSpeed = Double.NaN;
        gnssAt = 0L;
        windowSpeed = Double.NaN;
        windowAt = 0L;
        windowEstimated = false;
        source = Source.NONE;
    }

    /** Formats seconds-per-kilometre as {@code m'ss"}, the convention runners read fastest. */
    static String formatPace(double secondsPerKm) {
        if (!Double.isFinite(secondsPerKm) || secondsPerKm <= 0d || secondsPerKm > 5_999d) return "--'--\"";
        int total = (int)Math.round(secondsPerKm);
        return String.format(java.util.Locale.CHINA, "%d'%02d\"", total / 60, total % 60);
    }
}
