package com.poyi.watchintervals;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted spoken-cue settings shared by the workout service and preview screen. */
final class WorkoutVoiceSettings {
    private static final String PREFS = "workout_voice_cues";
    private static final String ENABLED = "enabled";
    private static final String PRESET = "preset";

    private WorkoutVoiceSettings() {}

    static boolean enabled(Context context) {
        return preferences(context).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply();
    }

    static WorkoutVoiceCuePolicy.Preset preset(Context context) {
        return WorkoutVoiceCuePolicy.Preset.fromName(
                preferences(context).getString(PRESET, null));
    }

    static void setPreset(Context context, WorkoutVoiceCuePolicy.Preset preset) {
        preferences(context).edit().putString(PRESET, preset.name()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
