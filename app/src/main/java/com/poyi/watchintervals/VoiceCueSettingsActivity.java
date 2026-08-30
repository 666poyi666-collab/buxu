package com.poyi.watchintervals;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.EnumMap;

/** On-watch voice-cue settings and offline TTS preview. */
public final class VoiceCueSettingsActivity extends WatchActivity {
    private final EnumMap<WorkoutVoiceCuePolicy.Preset, TextView> presetButtons =
            new EnumMap<>(WorkoutVoiceCuePolicy.Preset.class);
    private Switch enabled;
    private TextView currentPreset;
    private WorkoutVoiceSpeaker speaker;
    private boolean renderingSettings;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        speaker = new WorkoutVoiceSpeaker(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 6),
                Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 8));
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.backButton(this);
        header.addView(back, new LinearLayout.LayoutParams(
                Ui.dp(this, 34), Ui.dp(this, 34)));
        TextView title = Ui.bold(this, "训练提示音", 20, Ui.WHITE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1);
        titleParams.leftMargin = Ui.dp(this, 9);
        header.addView(title, titleParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 12), 0);
        toggleRow.setBackground(Ui.background(this, Ui.PANEL, Ui.RADIUS_CARD));
        enabled = new Switch(this);
        enabled.setText("语音提示");
        enabled.setTextColor(Ui.WHITE);
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.dp(this, 14));
        enabled.setShowText(false);
        int[][] toggleStates = {
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        enabled.setThumbTintList(new ColorStateList(toggleStates,
                new int[]{Ui.LIME, Ui.MUTED}));
        enabled.setTrackTintList(new ColorStateList(toggleStates,
                new int[]{Ui.TINT_LIME, Ui.LINE}));
        enabled.setContentDescription("训练语音提示开关");
        toggleRow.addView(enabled, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams toggleParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        toggleParams.topMargin = Ui.dp(this, 8);
        root.addView(toggleRow, toggleParams);

        TextView voiceLabel = Ui.bold(this, "音色", Ui.LABEL, Ui.MUTED);
        root.addView(voiceLabel, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));

        LinearLayout presets = new LinearLayout(this);
        presets.setGravity(Gravity.CENTER);
        for (WorkoutVoiceCuePolicy.Preset preset : WorkoutVoiceCuePolicy.Preset.values()) {
            TextView button = Ui.action(this, preset.label, 13, Ui.WHITE, Ui.PANEL);
            button.setContentDescription("选择" + preset.label + "音色");
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
            if (!presetButtons.isEmpty()) params.leftMargin = Ui.dp(this, 5);
            presets.addView(button, params);
            presetButtons.put(preset, button);
            button.setOnClickListener(v -> {
                WorkoutVoiceSettings.setPreset(this, preset);
                renderSettings();
                preview();
            });
        }
        root.addView(presets, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        LinearLayout facts = new LinearLayout(this);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setPadding(Ui.dp(this, 14), Ui.dp(this, 9),
                Ui.dp(this, 14), Ui.dp(this, 9));
        facts.setBackground(Ui.background(this, Ui.PANEL, Ui.RADIUS_CARD));
        currentPreset = Ui.bold(this, "", 15, Ui.WHITE);
        facts.addView(currentPreset, new LinearLayout.LayoutParams(-1, Ui.dp(this, 25)));
        TextView timing = Ui.text(this, "提前提示  5 秒 / 50 米", Ui.LABEL, Ui.MUTED);
        facts.addView(timing, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));
        LinearLayout.LayoutParams factsParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        factsParams.topMargin = Ui.dp(this, 10);
        root.addView(facts, factsParams);

        root.addView(new TextView(this), new LinearLayout.LayoutParams(-1, 0, 1));

        TextView preview = Ui.iconAction(this, "试听下一阶段", 16,
                Ui.BLACK, Ui.LIME, Ui.Symbol.SOUND);
        preview.setOnClickListener(v -> preview());
        root.addView(preview,
                new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_PRIMARY)));

        TextView done = Ui.iconAction(this, "返回", 15,
                Ui.WHITE, Ui.PANEL, Ui.Symbol.BACK);
        LinearLayout.LayoutParams doneParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_SECONDARY));
        doneParams.topMargin = Ui.dp(this, 8);
        root.addView(done, doneParams);

        enabled.setOnCheckedChangeListener((button, checked) -> {
            if (renderingSettings) return;
            WorkoutVoiceSettings.setEnabled(this, checked);
            renderSettings();
            if (checked) preview();
            else speaker.stop();
        });
        back.setOnClickListener(v -> finish());
        done.setOnClickListener(v -> finish());
        setContentView(root);
        renderSettings();
    }

    private void renderSettings() {
        boolean active = WorkoutVoiceSettings.enabled(this);
        renderingSettings = true;
        if (enabled.isChecked() != active) enabled.setChecked(active);
        renderingSettings = false;
        WorkoutVoiceCuePolicy.Preset selected = WorkoutVoiceSettings.preset(this);
        Ui.setTextIfChanged(currentPreset,
                active ? selected.label + "音色 · 已开启" : "语音提示已关闭");
        Ui.setTextColorIfChanged(currentPreset, active ? Ui.LIME : Ui.MUTED);
        for (WorkoutVoiceCuePolicy.Preset preset : presetButtons.keySet()) {
            TextView button = presetButtons.get(preset);
            boolean chosen = preset == selected;
            Ui.styleAction(this, button,
                    chosen ? Ui.BLACK : Ui.WHITE, chosen ? Ui.LIME : Ui.PANEL);
            button.setSelected(chosen);
        }
    }

    private void preview() {
        if (!WorkoutVoiceSettings.enabled(this)) return;
        speaker.speak("还有5秒，下一阶段，快走2分钟");
    }

    @Override protected void onDestroy() {
        if (speaker != null) speaker.close();
        speaker = null;
        super.onDestroy();
    }
}
