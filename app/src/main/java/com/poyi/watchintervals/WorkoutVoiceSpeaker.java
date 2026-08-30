package com.poyi.watchintervals;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Low-overhead wrapper around the watch's installed offline Chinese TTS engine. */
final class WorkoutVoiceSpeaker implements AutoCloseable {
    private final Context context;
    private final AtomicLong utteranceSequence = new AtomicLong();
    private TextToSpeech engine;
    private boolean ready;
    private boolean closed;
    private String pendingText;

    WorkoutVoiceSpeaker(Context context) {
        this.context = context.getApplicationContext();
        initialize();
    }

    private void initialize() {
        engine = new TextToSpeech(context, status -> {
            if (closed || engine == null) return;
            if (status != TextToSpeech.SUCCESS) {
                android.util.Log.w("WorkoutVoice", "TTS initialization failed: " + status);
                return;
            }
            int language = engine.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (language == TextToSpeech.LANG_MISSING_DATA
                    || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                android.util.Log.w("WorkoutVoice", "Chinese TTS voice is unavailable");
                return;
            }
            engine.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ready = true;
            applyPreset();
            if (pendingText != null) {
                String value = pendingText;
                pendingText = null;
                speak(value);
            }
        });
    }

    synchronized void speak(String text) {
        if (closed || text == null || text.trim().isEmpty()
                || !WorkoutVoiceSettings.enabled(context)) return;
        if (!ready || engine == null) {
            pendingText = text;
            return;
        }
        applyPreset();
        Bundle parameters = new Bundle();
        parameters.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM,
                android.media.AudioManager.STREAM_MUSIC);
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, parameters,
                "workout-cue-" + utteranceSequence.incrementAndGet());
    }

    synchronized void stop() {
        pendingText = null;
        if (engine != null) engine.stop();
    }

    private void applyPreset() {
        if (engine == null) return;
        WorkoutVoiceCuePolicy.Preset preset = WorkoutVoiceSettings.preset(context);
        engine.setSpeechRate(preset.speechRate);
        engine.setPitch(preset.pitch);
    }

    @Override public synchronized void close() {
        closed = true;
        pendingText = null;
        ready = false;
        if (engine != null) {
            engine.stop();
            engine.shutdown();
            engine = null;
        }
    }
}
