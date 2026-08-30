package com.poyi.watchintervals;

/** Pure phrase, timing and voice-preset rules for spoken workout guidance. */
final class WorkoutVoiceCuePolicy {
    static final long TIME_PREVIEW_MILLIS = 5_000L;
    static final double DISTANCE_PREVIEW_METERS = 50d;

    enum Preset {
        CLEAR("清晰", 1.00f, 1.00f),
        CALM("沉稳", 0.88f, 0.84f),
        ENERGETIC("活力", 1.08f, 1.12f);

        final String label;
        final float speechRate;
        final float pitch;

        Preset(String label, float speechRate, float pitch) {
            this.label = label;
            this.speechRate = speechRate;
            this.pitch = pitch;
        }

        static Preset fromName(String value) {
            try { return value == null ? CLEAR : valueOf(value); }
            catch (IllegalArgumentException ignored) { return CLEAR; }
        }
    }

    private WorkoutVoiceCuePolicy() {}

    static String stageAnnouncement(int stageNumber, Stage stage) {
        return "第" + Math.max(1, stageNumber) + "阶段，" + stage.name() + spokenTarget(stage);
    }

    static String upcomingAnnouncement(Stage current, double stageMeters,
            long stageMillis, Stage next) {
        if (current.unit == Stage.Unit.TIME) {
            long remainingMillis = Math.max(0L, current.target * 1_000L - stageMillis);
            long seconds = Math.max(1L, (remainingMillis + 999L) / 1_000L);
            return "还有" + seconds + "秒，下一阶段，" + next.name() + spokenTarget(next);
        }
        long meters = Math.max(1L, Math.round(current.target - stageMeters));
        return "还有" + meters + "米，下一阶段，" + next.name() + spokenTarget(next);
    }

    static boolean shouldPreview(Stage stage, double stageMeters, long stageMillis) {
        if (stage.unit == Stage.Unit.TIME) {
            long remaining = stage.target * 1_000L - stageMillis;
            return remaining > 0L && remaining <= TIME_PREVIEW_MILLIS;
        }
        double remaining = stage.target - stageMeters;
        return remaining > 0d && remaining <= DISTANCE_PREVIEW_METERS;
    }

    static String completionAnnouncement() {
        return "训练计划已完成，继续自由记录";
    }

    private static String spokenTarget(Stage stage) {
        if (stage.unit == Stage.Unit.DISTANCE) {
            if (stage.target >= 1_000 && stage.target % 1_000 == 0) {
                return (stage.target / 1_000) + "公里";
            }
            return stage.target + "米";
        }
        int minutes = stage.target / 60;
        int seconds = stage.target % 60;
        if (minutes > 0 && seconds > 0) return minutes + "分钟" + seconds + "秒";
        if (minutes > 0) return minutes + "分钟";
        return seconds + "秒";
    }
}
