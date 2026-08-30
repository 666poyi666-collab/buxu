package com.poyi.watchintervals.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Presentation rules for the phone plan library and editor. */
public final class PhonePlanUiModel {
    private PhonePlanUiModel() {}

    public static String summary(JSONArray stages) {
        if (stages == null || stages.length() == 0) return "暂无训练内容";
        long meters = 0L;
        long seconds = 0L;
        for (int index = 0; index < stages.length(); index++) {
            JSONObject stage = stages.optJSONObject(index);
            if (stage == null) continue;
            long target = Math.max(0L, stage.optLong("target"));
            if ("DISTANCE".equals(stage.optString("unit"))) meters += target;
            else seconds += target;
        }
        ArrayList<String> pieces = new ArrayList<>();
        pieces.add(stages.length() + " 阶段");
        if (seconds > 0L) pieces.add(duration(seconds));
        if (meters > 0L) pieces.add(distance(meters));
        return join(" · ", pieces);
    }

    public static String compactSequence(JSONArray stages) {
        if (stages == null || stages.length() == 0) return "还没有训练阶段";
        int repeatUnit = repeatingUnit(stages);
        int shown = repeatUnit > 0 ? repeatUnit : Math.min(4, stages.length());
        ArrayList<String> labels = new ArrayList<>();
        for (int index = 0; index < shown; index++) {
            JSONObject stage = stages.optJSONObject(index);
            if (stage != null) labels.add(stageLabel(stage));
        }
        String result = join("  →  ", labels);
        if (repeatUnit > 0) return result + "  × " + (stages.length() / repeatUnit);
        return shown < stages.length() ? result + "  →  另 " + (stages.length() - shown) + " 阶段" : result;
    }

    public static String stageLabel(JSONObject stage) {
        if (stage == null) return "未知阶段";
        String kind = kindName(stage.optString("kind"));
        int target = Math.max(1, stage.optInt("target", 1));
        return kind + " " + targetLabel(stage.optString("unit"), target);
    }

    public static String kindName(String kind) {
        if ("WALK".equals(kind)) return "快走";
        if ("REST".equals(kind)) return "休息";
        return "跑步";
    }

    public static String nextKind(String kind) {
        if ("RUN".equals(kind)) return "WALK";
        if ("WALK".equals(kind)) return "REST";
        return "RUN";
    }

    public static int defaultTarget(String kind, String unit) {
        if ("TIME".equals(unit)) {
            if ("REST".equals(kind)) return 60;
            if ("WALK".equals(kind)) return 120;
            return 300;
        }
        return "WALK".equals(kind) ? 200 : 1000;
    }

    /** Unit changes reset to a meaningful target; preserving 1000 as seconds was destructive. */
    public static int convertedTarget(String kind, String fromUnit, String toUnit, int current) {
        if (fromUnit != null && fromUnit.equals(toUnit)) return Math.max(1, current);
        return defaultTarget(kind, toUnit);
    }

    public static String normalizedUnit(String kind, String requestedUnit) {
        return "REST".equals(kind) ? "TIME"
                : "DISTANCE".equals(requestedUnit) ? "DISTANCE" : "TIME";
    }

    private static int repeatingUnit(JSONArray stages) {
        int count = stages.length();
        for (int unit = 1; unit <= count / 2; unit++) {
            if (count % unit != 0) continue;
            boolean matches = true;
            for (int index = unit; index < count; index++) {
                if (!sameStage(stages.optJSONObject(index), stages.optJSONObject(index % unit))) {
                    matches = false;
                    break;
                }
            }
            if (matches) return unit;
        }
        return 0;
    }

    private static boolean sameStage(JSONObject left, JSONObject right) {
        return left != null && right != null
                && left.optString("kind").equals(right.optString("kind"))
                && left.optString("unit").equals(right.optString("unit"))
                && left.optInt("target") == right.optInt("target");
    }

    private static String targetLabel(String unit, int target) {
        return "DISTANCE".equals(unit) ? distance(target) : duration(target);
    }

    private static String duration(long seconds) {
        if (seconds >= 60L && seconds % 60L == 0L) return (seconds / 60L) + " 分钟";
        if (seconds > 60L) return (seconds / 60L) + " 分 " + (seconds % 60L) + " 秒";
        return seconds + " 秒";
    }

    private static String distance(long meters) {
        if (meters >= 1000L && meters % 1000L == 0L) return (meters / 1000L) + " 公里";
        if (meters >= 1000L) return String.format(java.util.Locale.CHINA, "%.2f 公里", meters / 1000d);
        return meters + " 米";
    }

    private static String join(String separator, ArrayList<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }
}
