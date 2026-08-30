package com.poyi.watchintervals.phone;

import java.util.Locale;

/**
 * Pure display formatters shared by the phone screens, android-free so the JVM suite covers
 * them. MainActivity and HistoryDetailActivity each carried private copies before; the pace
 * ones had already split ("5:32 /公里" in the overview card, formatDuration-built "05:32 /公里"
 * three cards below it on the same screen).
 *
 * <p>The phone deliberately speaks Chinese units ("公里", "5:32 /公里") while the watch speaks
 * the instrument idiom ("km", 5'32") — companion text versus dial figures.
 */
public final class PhoneFormat {
    private PhoneFormat() {}

    /** Duration clock: mm:ss, rolling to h:mm:ss past the hour. */
    public static String duration(long millis) {
        long total = Math.max(0, millis / 1000), hours = total / 3600, minutes = (total % 3600) / 60, seconds = total % 60;
        if (hours > 0) return String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.CHINA, "%02d:%02d", minutes, seconds);
    }

    /** Distance: whole 米 below one kilometre, two-decimal 公里 from there on. */
    public static String distance(double meters) {
        if (meters < 1000) return Math.round(meters) + " 米";
        return String.format(Locale.CHINA, "%.2f 公里", meters / 1000d);
    }

    /** Average pace from a duration over a distance. */
    public static String pace(long millis, double meters) {
        if (meters < 1 || millis <= 0) return "-- /公里";
        return paceSeconds(Math.round(millis / 1000d * 1000d / meters));
    }

    /** Pace from seconds-per-km, the unit splits and best-pace figures are stored in. */
    public static String paceSeconds(long secondsPerKm) {
        if (secondsPerKm <= 0) return "-- /公里";
        return String.format(Locale.CHINA, "%d:%02d /公里", secondsPerKm / 60, secondsPerKm % 60);
    }

    /**
     * Sleep-style duration in words. "7小时12分" is how a person reads a night; the stopwatch
     * notation the rows used before ("7:12:00") belongs on a workout clock.
     */
    public static String minutesHuman(int minutes) {
        int clamped = Math.max(0, minutes);
        int hours = clamped / 60, rest = clamped % 60;
        if (hours == 0) return rest + "分";
        return rest == 0 ? hours + "小时" : hours + "小时" + rest + "分";
    }
}
