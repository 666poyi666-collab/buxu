package com.poyi.watchintervals;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private static final float WATCH_SCALE = 1.35f;
    private static final float MIN_TOUCH_TARGET = 40f;
    private static final PathInterpolator PRESS_IN = new PathInterpolator(.2f, 0f, .2f, 1f);
    private static final PathInterpolator PRESS_OUT = new PathInterpolator(.33f, 0f, .67f, 1f);

    // OWW221 is AMOLED: a pure black background leaves those pixels physically unlit, which both
    // saves panel power on a long run and gives the bezel-less look a real product has. The old
    // near-black (7,9,10) lit every pixel on screen for no visual gain.
    // 令牌集中在 WatchTokens,这里只做转发:既有调用点保持不变,同时消除重复定义。
    // OWW221 is AMOLED: a pure black background leaves those pixels physically unlit, which both
    // saves panel power on a long run and gives the bezel-less look a real product has.
    static final int BLACK = WatchTokens.BLACK;
    // Watch workout palette: true-black canvas, Apple's neutral greys and one semantic colour
    // per metric family. Bright colours are reserved for live data, never used as decoration.
    static final int PANEL = WatchTokens.PANEL;
    static final int PANEL_ACTIVE = WatchTokens.PANEL_ACTIVE;
    static final int WHITE = WatchTokens.WHITE;
    static final int MUTED = WatchTokens.MUTED;
    static final int LINE = WatchTokens.LINE;
    static final int LIME = WatchTokens.LIME;
    static final int YELLOW = WatchTokens.YELLOW;
    static final int CYAN = WatchTokens.CYAN;
    static final int AMBER = WatchTokens.AMBER;
    static final int RED = WatchTokens.RED;
    static final int GREEN = WatchTokens.GREEN;
    static final int BRAND = WatchTokens.BRAND;
    /** 语义浅色底、强调面板底与浮层遮罩。 */
    static final int TINT_LIME = WatchTokens.TINT_LIME;
    static final int TINT_CYAN = WatchTokens.TINT_CYAN;
    static final int TINT_AMBER = WatchTokens.TINT_AMBER;
    static final int PANEL_LIME_EDGE = WatchTokens.PANEL_LIME_EDGE;
    static final int PANEL_ROUTE = WatchTokens.PANEL_ROUTE;
    static final int SCRIM = WatchTokens.SCRIM;

    // One type scale instead of per-screen magic numbers, so headings and labels line up across
    // the home, training, plan and history pages. Figure sizes are measured off the stock
    // HeySports workout screen (378px captures / 1.35 canvas scale).
    static final float DISPLAY = WatchTokens.DISPLAY;
    static final float TITLE = WatchTokens.TITLE;
    static final float HEADLINE = WatchTokens.HEADLINE;
    static final float BODY = WatchTokens.BODY;
    static final float LABEL = WatchTokens.LABEL;
    static final float CAPTION = WatchTokens.CAPTION;
    /** Stock sports app: the leading elapsed-time figure. */
    static final float FIGURE_HERO = WatchTokens.FIGURE_HERO;
    /** Stock sports app: every other metric figure on the workout page. */
    static final float FIGURE = WatchTokens.FIGURE;
    /** Inline unit/label that trails a figure at its baseline. */
    static final float FIGURE_LABEL = WatchTokens.FIGURE_LABEL;
    static final float STAGE_METRIC_FIGURE = WatchTokens.STAGE_METRIC_FIGURE;
    static final float STAGE_METRIC_ROW = WatchTokens.STAGE_METRIC_ROW;
    static final float STAGE_METRIC_GAP = WatchTokens.STAGE_METRIC_GAP;
    /** Page side padding. The stock app runs nearly edge-to-edge. */
    static final float PAGE_MARGIN = WatchTokens.PAGE_MARGIN;
    static final float RADIUS_CARD = WatchTokens.RADIUS_CARD;
    static final float RADIUS_CHIP = WatchTokens.RADIUS_CHIP;
    static final float RADIUS_ROUTE = WatchTokens.RADIUS_ROUTE;
    static final float HEADER_ICON = WatchTokens.HEADER_ICON;
    static final float ACTION_PRIMARY = WatchTokens.ACTION_PRIMARY;
    static final float ACTION_SECONDARY = WatchTokens.ACTION_SECONDARY;
    static final float ACTION_CONTROL = WatchTokens.ACTION_CONTROL;
    static final float LIST_ROW = WatchTokens.LIST_ROW;

    private Ui() {}

    static int rgb(int red, int green, int blue) { return Color.rgb(red, green, blue); }

    private static float scale(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float widthScale = metrics.widthPixels / 378f;
        float heightScale = metrics.heightPixels / 496f;
        return WATCH_SCALE * Math.min(widthScale, heightScale);
    }

    static int dp(Context context, float value) {
        // Keep the OWW221 calibration while preserving proportions on other watch canvases.
        return Math.round(value * scale(context));
    }

    /**
     * Respect the system's readable-text preference without letting a 378 x 496 fixed-height
     * instrument panel collapse. Body copy gets the largest bounded increase; hero workout
     * figures keep their calibrated size because their surrounding unit/label already conveys
     * the value and their rows cannot safely grow.
     */
    private static float textScale(Context context, float sizeDp) {
        float requested = context.getResources().getConfiguration().fontScale;
        if (!Float.isFinite(requested) || requested <= 0f) requested = 1f;
        if (sizeDp > TITLE) return 1f;
        float maximum = sizeDp <= BODY ? 1.25f : sizeDp <= HEADLINE ? 1.20f : 1.12f;
        return Math.max(.90f, Math.min(maximum, requested));
    }

    static float textPixels(Context context, float sizeDp) {
        return sizeDp * scale(context) * textScale(context, sizeDp);
    }

    private static <T extends TextView> T configureText(
            T view, Context context, String value, float sizeDp, int color) {
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPixels(context, sizeDp));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(0);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    static TextView text(Context context, String value, float sizeDp, int color) {
        return configureText(new TextView(context), context, value, sizeDp, color);
    }

    static TextView bold(Context context, String value, float sizeDp, int color) {
        TextView view = text(context, value, sizeDp, color);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        return view;
    }

    // A broad tabular face is closer to the legibility of a modern sports watch than condensed
    // digits. It also keeps punctuation such as 00:18:42 from visually collapsing.
    private static final Typeface NUMERAL_FACE = Typeface.create("sans-serif", Typeface.BOLD);

    /**
     * Big-figure text: condensed bold with tabular digits so a ticking value keeps a fixed width
     * instead of wobbling every second.
     */
    static TextView numeral(Context context, String value, float sizeDp, int color) {
        TextView view = text(context, value, sizeDp, color);
        view.setTypeface(NUMERAL_FACE);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    static GradientDrawable background(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static GradientDrawable outlinedBackground(Context context, int color, int stroke, float radiusDp) {
        GradientDrawable drawable = background(context, color, radiusDp);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static TextView action(Context context, String value, float sizeDp, int foreground, int background) {
        TextView view = configureText(new MinimumTouchTargetTextView(context),
                context, value, sizeDp, foreground);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(45, 255, 255, 255)), background(context, background, RADIUS_CARD), null));
        view.setClickable(true);
        view.setFocusable(true);
        pressable(view);
        return view;
    }

    enum Symbol { PLAY, PAUSE, STOP, LIST, BACK, FORWARD, DELETE, CHECK, HISTORY, SOUND }

    static TextView iconAction(Context context, String value, float sizeDp, int foreground,
            int background, Symbol symbol) {
        TextView view = action(context, value, sizeDp, foreground, background);
        setActionSymbol(context, view, symbol, foreground);
        return view;
    }

    static TextView controlButton(Context context, String text, Symbol symbol, int symbolColor, int badgeColor) {
        TextView view = action(context, text, 16, WHITE, PANEL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        BadgeDrawable badge = new BadgeDrawable(context, symbol, symbolColor, badgeColor, 34);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(badge, null, null, null);
        view.setCompoundDrawablePadding(dp(context, 14));
        return view;
    }

    static void updateControlButton(Context context, TextView view, String text, Symbol symbol, int symbolColor, int badgeColor) {
        view.setText(text);
        BadgeDrawable badge = new BadgeDrawable(context, symbol, symbolColor, badgeColor, 34);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(badge, null, null, null);
    }

    static void setActionSymbol(Context context, TextView view, Symbol symbol, int color) {
        Drawable icon = new SymbolDrawable(context, symbol, color);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        view.setCompoundDrawablePadding(dp(context, 8));
    }

    static void styleAction(Context context, TextView view, int foreground, int background) {
        view.setTextColor(foreground);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
                background(context, background, RADIUS_CARD), null));
    }

    private static final class BadgeDrawable extends Drawable {
        private final int size;
        private final Symbol symbol;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        BadgeDrawable(Context context, Symbol symbol, int symbolColor, int bgColor, int sizeDp) {
            this.size = dp(context, sizeDp);
            this.symbol = symbol;
            bgPaint.setColor(bgColor);
            bgPaint.setStyle(Paint.Style.FILL);
            symbolPaint.setColor(symbolColor);
            symbolPaint.setAntiAlias(true);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float radius = size / 2f;
            canvas.drawCircle(cx, cy, radius, bgPaint);
            float inner = size * 0.44f;
            float left = cx - inner / 2f, top = cy - inner / 2f;
            float width = inner, height = inner;
            path.reset();
            symbolPaint.setStyle(Paint.Style.FILL);
            if (symbol == Symbol.PLAY) {
                path.moveTo(left + width * 0.28f, top + height * 0.16f);
                path.lineTo(left + width * 0.84f, cy);
                path.lineTo(left + width * 0.28f, top + height * 0.84f);
                path.close();
                canvas.drawPath(path, symbolPaint);
            } else if (symbol == Symbol.PAUSE) {
                float barW = width * 0.26f;
                canvas.drawRoundRect(left + width * 0.15f, top + height * 0.16f,
                        left + width * 0.15f + barW, top + height * 0.84f, 3, 3, symbolPaint);
                canvas.drawRoundRect(left + width * 0.59f, top + height * 0.16f,
                        left + width * 0.59f + barW, top + height * 0.84f, 3, 3, symbolPaint);
            } else if (symbol == Symbol.STOP) {
                float pad = width * 0.14f;
                canvas.drawRoundRect(left + pad, top + pad, left + width - pad, top + height - pad,
                        4, 4, symbolPaint);
            }
        }

        @Override public void setAlpha(int alpha) { bgPaint.setAlpha(alpha); symbolPaint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            bgPaint.setColorFilter(filter); symbolPaint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final class SymbolDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Symbol symbol;
        private final int size;

        SymbolDrawable(Context context, Symbol symbol, int color) {
            this.symbol = symbol;
            this.size = dp(context, 20);
            paint.setColor(color);
            paint.setStrokeWidth(dp(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float left = b.left, top = b.top, width = b.width(), height = b.height();
            float cx = left + width / 2f, cy = top + height / 2f;
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (symbol) {
                case PLAY:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(left + width * .33f, top + height * .22f);
                    path.lineTo(left + width * .78f, cy);
                    path.lineTo(left + width * .33f, top + height * .78f);
                    path.close(); canvas.drawPath(path, paint); break;
                case PAUSE:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(left + width * .28f, top + height * .22f,
                            left + width * .43f, top + height * .78f, 2, 2, paint);
                    canvas.drawRoundRect(left + width * .57f, top + height * .22f,
                            left + width * .72f, top + height * .78f, 2, 2, paint); break;
                case STOP:
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(left + width * .28f, top + height * .28f,
                            left + width * .72f, top + height * .72f, 3, 3, paint); break;
                case LIST:
                    for (int i = 0; i < 3; i++) {
                        float y = top + height * (.28f + i * .22f);
                        canvas.drawCircle(left + width * .23f, y, paint.getStrokeWidth() * .55f, paint);
                        canvas.drawLine(left + width * .38f, y, left + width * .78f, y, paint);
                    } break;
                case BACK:
                    path.moveTo(left + width * .66f, top + height * .22f);
                    path.lineTo(left + width * .38f, cy);
                    path.lineTo(left + width * .66f, top + height * .78f);
                    canvas.drawPath(path, paint); break;
                case FORWARD:
                    path.moveTo(left + width * .34f, top + height * .22f);
                    path.lineTo(left + width * .62f, cy);
                    path.lineTo(left + width * .34f, top + height * .78f);
                    canvas.drawPath(path, paint); break;
                case DELETE:
                    canvas.drawLine(left + width * .28f, top + height * .32f,
                            left + width * .72f, top + height * .32f, paint);
                    canvas.drawRoundRect(left + width * .34f, top + height * .38f,
                            left + width * .66f, top + height * .78f, 3, 3, paint);
                    canvas.drawLine(left + width * .42f, top + height * .24f,
                            left + width * .58f, top + height * .24f, paint); break;
                case CHECK:
                    path.moveTo(left + width * .22f, cy);
                    path.lineTo(left + width * .43f, top + height * .7f);
                    path.lineTo(left + width * .79f, top + height * .28f);
                    canvas.drawPath(path, paint); break;
                case HISTORY:
                    canvas.drawCircle(cx, cy, width * .31f, paint);
                    canvas.drawLine(cx, cy, cx, top + height * .32f, paint);
                    canvas.drawLine(cx, cy, left + width * .64f, top + height * .58f, paint); break;
                case SOUND:
                    path.moveTo(left + width * .22f, top + height * .42f);
                    path.lineTo(left + width * .38f, top + height * .42f);
                    path.lineTo(left + width * .56f, top + height * .27f);
                    path.lineTo(left + width * .56f, top + height * .73f);
                    path.lineTo(left + width * .38f, top + height * .58f);
                    path.lineTo(left + width * .22f, top + height * .58f);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawArc(left + width * .52f, top + height * .35f,
                            left + width * .76f, top + height * .65f,
                            -55f, 110f, false, paint); break;
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter); invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * Tactile press treatment shared by real actions. The scale animation runs on RenderThread;
     * it adds physical feedback without forcing a layout pass or allocating a new drawable.
     */
    static <T extends View> T pressable(T view) {
        int minimum = dp(view.getContext(), MIN_TOUCH_TARGET);
        view.setMinimumWidth(Math.max(view.getMinimumWidth(), minimum));
        view.setMinimumHeight(Math.max(view.getMinimumHeight(), minimum));
        view.setOnTouchListener((target, event) -> {
            if (!target.isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                target.animate().cancel();
                target.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.90f)
                        .setDuration(66L)
                        .setInterpolator(PRESS_IN)
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().cancel();
                target.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(150L)
                        .setInterpolator(PRESS_OUT)
                        .start();
                // A pager/ScrollView cancels the child once the gesture becomes navigation.
                // Confirm only a real in-bounds release, so swiping from a large button does not
                // feel like an accidental tap.
                if (action == MotionEvent.ACTION_UP
                        && event.getX() >= 0f && event.getX() < target.getWidth()
                        && event.getY() >= 0f && event.getY() < target.getHeight()) {
                    target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            // Let the View keep ownership of click/long-click and ripple semantics.
            return false;
        });
        return view;
    }

    /**
     * Layouts predating the accessibility pass still request 34/36dp back discs. Android's
     * ordinary minimum size loses to an EXACTLY spec, so actions enforce the 40dp hit target at
     * measurement time. The largest growth is 6dp and fits the existing 40dp watch headers.
     */
    private static final class MinimumTouchTargetTextView extends TextView {
        private final int minimumTarget;

        MinimumTouchTargetTextView(Context context) {
            super(context);
            minimumTarget = dp(context, MIN_TOUCH_TARGET);
            setMinimumWidth(minimumTarget);
            setMinimumHeight(minimumTarget);
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(Math.max(getMeasuredWidth(), minimumTarget),
                    Math.max(getMeasuredHeight(), minimumTarget));
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            Drawable[] drawables = getCompoundDrawablesRelative();
            Drawable left = drawables[0] != null ? drawables[0] : getCompoundDrawables()[0];
            int iconWidth = left != null ? left.getIntrinsicWidth() : 0;
            int pad = (left != null && iconWidth > 0) ? getCompoundDrawablePadding() : 0;
            CharSequence text = getText();
            float textWidth = (text != null && text.length() > 0) ? getPaint().measureText(text.toString()) : 0f;
            float contentWidth = iconWidth + (textWidth > 0 && iconWidth > 0 ? pad : 0) + textWidth;
            int totalWidth = getWidth();
            if (contentWidth > 0 && totalWidth > contentWidth) {
                float dx = (totalWidth - contentWidth) / 2f - getPaddingLeft();
                canvas.save();
                canvas.translate(dx, 0);
                super.onDraw(canvas);
                canvas.restore();
                return;
            }
            super.onDraw(canvas);
        }
    }

    /** One-shot entrance for countdown figures and transient workout cards. */
    static void popIn(View view) {
        view.animate().cancel();
        view.setAlpha(0.18f);
        view.setScaleX(0.72f);
        view.setScaleY(0.72f);
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220L).setInterpolator(PRESS_OUT).start();
    }

    /** Compact live-state chip used beside a page title or GPS readout. */
    static TextView chip(Context context, String value, int foreground, int background) {
        TextView view = bold(context, value, LABEL, foreground);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        view.setBackground(background(context, background, RADIUS_CHIP));
        return view;
    }

    static void playChime(boolean completion) {
        new Thread(() -> {
            try {
                int sampleRate = 22050;
                int f1 = completion ? 784 : 659;
                int f2 = completion ? 1046 : 880;
                int dur1 = (int) (sampleRate * 0.10);
                int dur2 = (int) (sampleRate * 0.18);
                int total = dur1 + dur2;
                short[] buffer = new short[total];
                for (int i = 0; i < dur1; i++) {
                    double t = (double) i / sampleRate;
                    double env = Math.sin(Math.PI * i / dur1);
                    buffer[i] = (short) (Math.sin(2 * Math.PI * f1 * t) * env * 22000);
                }
                for (int i = 0; i < dur2; i++) {
                    double t = (double) i / sampleRate;
                    double env = Math.sin(Math.PI * i / dur2);
                    buffer[dur1 + i] = (short) (Math.sin(2 * Math.PI * f2 * t) * env * 24000);
                }
                android.media.AudioAttributes attr = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                android.media.AudioFormat format = new android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build();
                android.media.AudioTrack track = new android.media.AudioTrack(attr, format,
                        buffer.length * 2, android.media.AudioTrack.MODE_STATIC,
                        android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
                track.write(buffer, 0, buffer.length);
                track.play();
                try { Thread.sleep((long) ((total / (double) sampleRate) * 1000) + 40); } catch (Exception ignored) {}
                track.release();
            } catch (Exception ignored) {}
        }).start();
    }

    /**
     * Apple-style metric cell: semantic label first, large tabular figure below, small unit on
     * the same baseline. The caller receives the figure so the one-second refresh only updates
     * the changing value.
     */
    static TextView metricCell(Context context, LinearLayout row, String label, String initial,
                               String unit, int color, float figureSize) {
        return metricCell(context, row, label, initial, unit, color, figureSize, false);
    }

    static TextView metricCell(Context context, LinearLayout row, String label, String initial,
                               String unit, int color, float figureSize, boolean asCard) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        if (asCard) {
            cell.setBackground(background(context, PANEL, RADIUS_CARD));
            cell.setPadding(dp(context, 7), dp(context, 2), dp(context, 7), dp(context, 2));
        }
        TextView caption = bold(context, label, asCard ? 10 : LABEL, color);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, dp(context, asCard ? 14 : 18)));
        LinearLayout figure = new LinearLayout(context);
        figure.setGravity(Gravity.BOTTOM);
        TextView value = numeral(context, initial, figureSize, color);
        value.setIncludeFontPadding(false);
        figure.addView(value, new LinearLayout.LayoutParams(-2, -2));
        if (unit != null && !unit.isEmpty()) {
            TextView suffix = text(context, unit, asCard ? 10 : LABEL, MUTED);
            suffix.setIncludeFontPadding(false);
            LinearLayout.LayoutParams suffixParams = new LinearLayout.LayoutParams(-2, -2);
            suffixParams.leftMargin = dp(context, 3);
            suffixParams.bottomMargin = dp(context, 1);
            figure.addView(suffix, suffixParams);
        }
        cell.addView(figure, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -1, 1);
        if (asCard) {
            cellParams.leftMargin = dp(context, 2);
            cellParams.rightMargin = dp(context, 2);
        }
        row.addView(cell, cellParams);
        return value;
    }

    /**
     * Original interval-route mark shared with the phone's WORKOUT symbol language.
     *
     * <p>A single open route and forward action stay crisp at 34dp and avoid the anatomical noise
     * of the previous tiny runner. Geometry is code-native and scales from a normalized viewport;
     * no font, vendor glyph or bitmap is involved.</p>
     */
    static final class WorkoutGlyph extends View {
        private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint action = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path actionPath = new Path();
        private final RectF routeBounds = new RectF();
        private final int color;
        private float left, top, scale;

        WorkoutGlyph(Context context, int color) {
            super(context);
            this.color = color;
            route.setStyle(Paint.Style.STROKE);
            route.setStrokeCap(Paint.Cap.ROUND);
            route.setStrokeJoin(Paint.Join.ROUND);
            action.setStyle(Paint.Style.FILL);
            // Every current instance sits next to a semantic title or inside an already-labelled
            // card. Announcing a generic "训练" for the decoration only duplicates TalkBack.
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private float x(float value) { return left + value * scale; }
        private float y(float value) { return top + value * scale; }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            scale = Math.min(width, height);
            left = (width - scale) / 2f;
            top = (height - scale) / 2f;

            route.setShader(null);
            action.setShader(null);
            route.setColor(color);
            action.setColor(color);
            route.setStrokeWidth(scale * .078f);
            routeBounds.set(x(.20f), y(.20f), x(.80f), y(.80f));

            // Same open-ring/forward-action grammar as PhoneSymbol.WORKOUT, tightened for 34dp.
            actionPath.reset();
            actionPath.moveTo(x(.43f), y(.36f));
            actionPath.lineTo(x(.68f), y(.50f));
            actionPath.lineTo(x(.43f), y(.64f));
            actionPath.close();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (scale <= 0f) return;
            canvas.drawArc(routeBounds, -78f, 293f, false, route);
            canvas.drawPath(actionPath, action);
        }
    }

    static WorkoutGlyph workoutGlyph(Context context, int color) {
        return new WorkoutGlyph(context, color);
    }

    /** Compact colour sequence for the current interval plan. */
    static final class StageStrip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF segment = new RectF();
        private final float gap;
        private int[] colors = new int[0];

        StageStrip(Context context) {
            super(context);
            gap = dp(context, 2);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setStages(java.util.List<Stage> stages) {
            int count = stages == null ? 0 : Math.min(12, stages.size());
            colors = new int[count];
            for (int index = 0; index < count; index++) {
                colors[index] = stageColor(stages.get(index).kind);
            }
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (colors.length == 0 || getWidth() <= 0 || getHeight() <= 0) return;
            float width = (getWidth() - gap * (colors.length - 1)) / colors.length;
            float radius = Math.min(getHeight() / 2f, dp(getContext(), 2));
            for (int index = 0; index < colors.length; index++) {
                float left = index * (width + gap);
                segment.set(left, 0, left + width, getHeight());
                paint.setColor(colors[index]);
                canvas.drawRoundRect(segment, radius, radius, paint);
            }
        }
    }

    static StageStrip stageStrip(Context context) { return new StageStrip(context); }

    /**
     * A truthful live heart-rate trace. Samples are added from real service snapshots only;
     * missing heart data leaves the graph empty instead of drawing a decorative fake waveform.
     */
    static final class HeartTrace extends View {
        private static final int MAX_SAMPLES = 48;
        private final int[] samples = new int[MAX_SAMPLES];
        private final int[] scratch = new int[MAX_SAMPLES];
        private int count;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path linePath = new Path();
        private final Path fillPath = new Path();
        private boolean pathDirty = true;
        private boolean expanded;

        HeartTrace(Context context) {
            super(context);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(context, 2.4f));
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setColor(RED);
            fill.setStyle(Paint.Style.FILL);
            panel.setColor(Color.rgb(12, 14, 16));
            guide.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            guide.setTextSize(textPixels(context, 11f));
        }

        void setExpanded(boolean value) {
            if (expanded == value) return;
            expanded = value;
            pathDirty = true;
            invalidate();
        }

        void addSample(int value) {
            if (value < 25 || value > 240) return;
            if (count < MAX_SAMPLES) samples[count++] = value;
            else {
                System.arraycopy(samples, 1, samples, 0, MAX_SAMPLES - 1);
                samples[MAX_SAMPLES - 1] = value;
            }
            pathDirty = true;
            invalidate();
        }

        void setSamples(java.util.List<Integer> values) {
            if (values == null || values.isEmpty()) { applyScratch(0); return; }
            int size = values.size();
            int nextCount = 0;
            for (int slot = 0; slot < Math.min(MAX_SAMPLES, size); slot++) {
                int index = Math.min(size - 1,
                        Math.round(slot * (size - 1f) / Math.max(1, Math.min(MAX_SAMPLES, size) - 1)));
                Integer value = values.get(index);
                if (value != null && value >= 25 && value <= 240) scratch[nextCount++] = value;
            }
            applyScratch(nextCount);
        }

        void setSamples(int[] values) {
            if (values == null || values.length == 0) { applyScratch(0); return; }
            int size = values.length;
            int nextCount = 0;
            for (int slot = 0; slot < Math.min(MAX_SAMPLES, size); slot++) {
                int index = Math.min(size - 1,
                        Math.round(slot * (size - 1f) / Math.max(1, Math.min(MAX_SAMPLES, size) - 1)));
                int value = values[index];
                if (value >= 25 && value <= 240) scratch[nextCount++] = value;
            }
            applyScratch(nextCount);
        }

        private void applyScratch(int nextCount) {
            if (nextCount == count) {
                boolean equal = true;
                for (int index = 0; index < nextCount; index++) {
                    if (samples[index] != scratch[index]) { equal = false; break; }
                }
                if (equal) return;
            }
            if (nextCount > 0) System.arraycopy(scratch, 0, samples, 0, nextCount);
            count = nextCount;
            pathDirty = true;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            fill.setShader(new LinearGradient(0, 0, 0, Math.max(1, height),
                    Color.argb(90, Color.red(RED), Color.green(RED), Color.blue(RED)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP));
            pathDirty = true;
        }

        private void rebuildPaths() {
            linePath.reset();
            fillPath.reset();
            pathDirty = false;
            if (count < 2 || getWidth() <= 0 || getHeight() <= 0) return;
            int min = samples[0], max = samples[0];
            for (int index = 1; index < count; index++) {
                min = Math.min(min, samples[index]);
                max = Math.max(max, samples[index]);
            }
            int spread = Math.max(12, max - min);
            float top = expanded ? dp(getContext(), 25) : dp(getContext(), 3);
            float bottom = expanded ? dp(getContext(), 7) : dp(getContext(), 3);
            float usableHeight = Math.max(1f, getHeight() - top - bottom);
            for (int index = 0; index < count; index++) {
                float x = count == 1 ? 0 : index * (getWidth() - 1f) / (count - 1f);
                float y = top + (max - samples[index] + (spread - (max - min)) / 2f)
                        * usableHeight / spread;
                if (index == 0) {
                    linePath.moveTo(x, y);
                    fillPath.moveTo(x, getHeight() - bottom);
                    fillPath.lineTo(x, y);
                } else {
                    linePath.lineTo(x, y);
                    fillPath.lineTo(x, y);
                }
            }
            fillPath.lineTo(getWidth(), getHeight() - bottom);
            fillPath.close();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (expanded) {
                float radius = dp(getContext(), 12);
                canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, panel);
                guide.setColor(MUTED);
                guide.setTextAlign(Paint.Align.LEFT);
                canvas.drawText("实时心率趋势", dp(getContext(), 10), dp(getContext(), 17), guide);
                if (count < 2) {
                    guide.setColor(Color.rgb(112, 112, 118));
                    guide.setTextAlign(Paint.Align.RIGHT);
                    canvas.drawText("佩戴后显示真实曲线",
                            getWidth() - dp(getContext(), 10), dp(getContext(), 17), guide);
                }
            }
            if (pathDirty) rebuildPaths();
            if (count < 2) return;
            canvas.drawPath(fillPath, fill);
            canvas.drawPath(linePath, line);
        }
    }

    static TextView iconAction(Context context, int drawableRes, String description, int foreground, int background) {
        TextView view = action(context, "", 1, foreground, background);
        view.setCompoundDrawablesWithIntrinsicBounds(drawableRes, 0, 0, 0);
        view.setCompoundDrawableTintList(ColorStateList.valueOf(foreground));
        view.setContentDescription(description);
        return view;
    }

    /**
     * Metric line in the stock sports idiom: a big figure with its unit/label trailing the
     * baseline ("0 公里", "--'--" 配速"), not pushed to the far edge. Returns the figure view;
     * the label view is returned via {@code labelOut[0]} when the caller needs to update it.
     */
    static TextView figureLine(Context context, LinearLayout parent, String initial, String label,
                               int color, float figureSize, float rowHeight, TextView[] labelOut) {
        LinearLayout row = new LinearLayout(context);
        // Baseline alignment keeps the small label sitting on the figure's baseline.
        TextView value = numeral(context, initial, figureSize, color);
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        TextView caption = text(context, label, FIGURE_LABEL, MUTED);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-2, -2);
        captionParams.leftMargin = dp(context, 6);
        row.addView(caption, captionParams);
        if (labelOut != null && labelOut.length > 0) labelOut[0] = caption;
        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(context, rowHeight)));
        return value;
    }

    /** Zone colours indexed 1..5 — the classic blue/green/yellow/orange/red training bands. */
    static final int[] ZONE_COLORS = {CYAN, GREEN, YELLOW, AMBER, RED};

    /**
     * Five-segment heart-rate zone band, the visual every serious running watch carries. The
     * current zone's segment is lit solid; the rest stay dim so intensity reads at a glance
     * without any numbers.
     */
    static final class ZoneBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private int zone;

        ZoneBar(Context context) { super(context); }

        void set(int value) {
            int clamped = Math.max(0, Math.min(5, value));
            if (clamped == zone) return;
            zone = clamped;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float gap = dp(getContext(), 3);
            float segment = (getWidth() - gap * 4f) / 5f;
            float radius = getHeight() / 2f;
            for (int index = 0; index < 5; index++) {
                boolean current = zone == index + 1;
                int color = ZONE_COLORS[index];
                paint.setColor(current ? color
                        : Color.argb(zone == 0 ? 46 : 34, Color.red(color), Color.green(color), Color.blue(color)));
                float left = index * (segment + gap);
                float top = current ? 0f : getHeight() * 0.18f;
                float bottom = current ? getHeight() : getHeight() * 0.82f;
                rect.set(left, top, left + segment, bottom);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }
    }

    /** Top bar shared by every page: small title left, live clock right, stock-sports style. */
    static TextView topBar(Context context, LinearLayout parent, TextView titleView) {
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1));
        TextView clock = numeral(context, "", 20, WHITE);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bar.addView(clock, new LinearLayout.LayoutParams(dp(context, 64), -1));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(context, 34)));
        return clock;
    }

    /**
     * Garmin-style grid cell: big figure over a small label, filling an equal share of its row.
     * Returns the figure view for updates.
     */
    static TextView gridCell(Context context, LinearLayout row, String initial, String label,
                             int color, float figureSize) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView value = numeral(context, initial, figureSize, color);
        cell.addView(value, new LinearLayout.LayoutParams(-2, -2));
        TextView caption = text(context, label, CAPTION, MUTED);
        cell.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        return value;
    }

    /** Back affordance shared by every secondary screen (minimum 40dp hit target). */
    static TextView backButton(Context context) {
        TextView view = configureText(new MinimumTouchTargetTextView(context),
                context, "", BODY, WHITE);
        view.setGravity(Gravity.CENTER);
        setActionSymbol(context, view, Symbol.BACK, WHITE);
        view.setCompoundDrawablePadding(0);
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
                background(context, PANEL, RADIUS_CARD), null));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription("返回");
        pressable(view);
        return view;
    }

    /**
     * Plan step row: stage-colour accent bar, muted order number, stage name, target figure
     * right-aligned in the numeral face. The previous rows glued index, name and target into one
     * plain string — the last "three fields in a sentence" list left after the instrument
     * redesign, with no hierarchy and none of the stage colour semantics the training pages use.
     */
    static LinearLayout stageRow(Context context, int index, Stage stage, int backgroundColor) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(background(context, backgroundColor, RADIUS_CARD));
        row.setPadding(dp(context, 12), 0, dp(context, 14), 0);
        View accent = new View(context);
        accent.setBackground(background(context, stageColor(stage.kind), 2));
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(context, 3.5f), dp(context, 16));
        accentParams.rightMargin = dp(context, 10);
        row.addView(accent, accentParams);
        TextView order = text(context, String.valueOf(index), BODY, MUTED);
        row.addView(order, new LinearLayout.LayoutParams(dp(context, 22), -1));
        TextView name = bold(context, stage.name(), BODY, WHITE);
        row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
        TextView target = numeral(context, stage.targetText(), 17, WHITE);
        target.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(target, new LinearLayout.LayoutParams(-2, -1));
        return row;
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(LINE);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(context, 1)));
        return view;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        card.setBackground(background(context, PANEL, RADIUS_CARD));
        return card;
    }

    /**
     * Page indicator drawn as real geometry.
     *
     * <p>The previous version rendered "●   ○   ○" as text, so the dot size, spacing and vertical
     * alignment were all at the mercy of the font's glyph metrics — the visible unevenness was the
     * single clearest "hand-made" tell on the home screen.
     */
    static final class PagerDots extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float radius, spacing;
        private final int count;
        private int active;

        PagerDots(Context context, int count, int active) {
            super(context);
            this.count = Math.max(1, count);
            this.active = active;
            radius = dp(context, 2.6f);
            spacing = dp(context, 9f);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setActive(int value) {
            if (value == active) return;
            active = value;
            invalidate();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize((int)Math.ceil(count * spacing), widthSpec),
                    resolveSize((int)Math.ceil(radius * 4f), heightSpec));
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float centerY = getHeight() / 2f;
            float startX = (getWidth() - (count - 1) * spacing) / 2f;
            for (int index = 0; index < count; index++) {
                boolean current = index == active;
                paint.setColor(current ? WHITE : LINE);
                canvas.drawCircle(startX + index * spacing, centerY, current ? radius : radius * 0.72f, paint);
            }
        }
    }

    static PagerDots pagerDots(Context context, int active, int count) {
        return new PagerDots(context, count, active);
    }

    /**
     * Circular stage-progress ring — the signature sports-watch visual. Track in {@link #LINE},
     * progress arc with round caps in the stage colour, starting at 12 o'clock.
     */
    static final class Ring extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bounds = new android.graphics.RectF();
        private final android.graphics.Path fullPath = new android.graphics.Path();
        private final android.graphics.Path progressPath = new android.graphics.Path();
        private final android.graphics.PathMeasure measure = new android.graphics.PathMeasure();
        private float fraction;
        private float cornerRadius;

        Ring(Context context) {
            super(context);
            float stroke = dp(context, 6f);
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(LINE);
            arc.setStyle(android.graphics.Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arc.setColor(LIME);
            cornerRadius = dp(context, 20f);
        }

        void set(float value, int color) {
            float clamped = Math.max(0f, Math.min(1f, value));
            if (clamped == fraction && color == arc.getColor()) return;
            fraction = clamped;
            arc.setColor(color);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float stroke = track.getStrokeWidth();
            float inset = stroke / 2f + dp(getContext(), 1);
            bounds.set(inset, inset, w - inset, h - inset);
            fullPath.reset();
            float cx = bounds.centerX();
            float r = Math.min(cornerRadius, Math.min(bounds.width(), bounds.height()) / 2f);
            fullPath.moveTo(cx, bounds.top);
            fullPath.lineTo(bounds.right - r, bounds.top);
            fullPath.arcTo(bounds.right - 2 * r, bounds.top, bounds.right, bounds.top + 2 * r, -90f, 90f, false);
            fullPath.lineTo(bounds.right, bounds.bottom - r);
            fullPath.arcTo(bounds.right - 2 * r, bounds.bottom - 2 * r, bounds.right, bounds.bottom, 0f, 90f, false);
            fullPath.lineTo(bounds.left + r, bounds.bottom);
            fullPath.arcTo(bounds.left, bounds.bottom - 2 * r, bounds.left + 2 * r, bounds.bottom, 90f, 90f, false);
            fullPath.lineTo(bounds.left, bounds.top + r);
            fullPath.arcTo(bounds.left, bounds.top, bounds.left + 2 * r, bounds.top + 2 * r, 180f, 90f, false);
            fullPath.lineTo(cx, bounds.top);
            fullPath.close();
            measure.setPath(fullPath, false);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(fullPath, track);
            if (fraction > 0f) {
                progressPath.reset();
                float total = measure.getLength();
                measure.getSegment(0f, total * fraction, progressPath, true);
                canvas.drawPath(progressPath, arc);
            }
        }
    }

    /** Skips the relayout that {@link TextView#setText} forces even when the string is unchanged. */
    static void setTextIfChanged(TextView view, CharSequence value) {
        if (view == null || value == null) return;
        if (!value.toString().contentEquals(view.getText())) view.setText(value);
    }

    /** Avoids invalidating text display lists when a semantic colour has not changed. */
    static void setTextColorIfChanged(TextView view, int color) {
        if (view != null && view.getCurrentTextColor() != color) view.setTextColor(color);
    }

    static void setTextAndColorIfChanged(TextView view, CharSequence value, int color) {
        setTextIfChanged(view, value);
        setTextColorIfChanged(view, color);
    }

    static int stageColor(Stage.Kind kind) {
        if (kind == Stage.Kind.WALK) return CYAN;
        if (kind == Stage.Kind.REST) return AMBER;
        return LIME;
    }

    /** Signal bands used by the stock sports preparation screen. */
    static String systemGpsSignal(int snr) {
        if (snr <= 0) return "";
        if (snr < 18) return "弱 " + snr;
        if (snr < 25) return "中 " + snr;
        return "强 " + snr;
    }
}
