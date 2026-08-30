package com.poyi.watchintervals.phone;

/** Light, data-first design tokens for the phone companion. */
public final class Palette {
    private Palette() {}

    public static final int BG = PhoneColorSpec.BG;
    public static final int NAV = PhoneColorSpec.NAV;
    public static final int CARD = PhoneColorSpec.CARD;
    public static final int CARD_HIGH = PhoneColorSpec.CARD_HIGH;
    public static final int CARD_DEEP = PhoneColorSpec.CARD_DEEP;
    public static final int BORDER = PhoneColorSpec.BORDER;
    /** Legacy View aliases retained only for HistoryDetailActivity. */
    public static final int GLASS_TOP = CARD;
    public static final int GLASS_BOTTOM = CARD;
    public static final int GLASS_BORDER = BORDER;
    public static final int GLASS_SELECTED = PhoneColorSpec.FILL_SELECTED;
    public static final int TEXT = PhoneColorSpec.TEXT;
    public static final int TEXT_DIM = PhoneColorSpec.TEXT_DIM;
    public static final int HINT = PhoneColorSpec.HINT;
    public static final int INK = PhoneColorSpec.INK;

    /** Original training accents; they don't reuse Apple's protected Activity Rings palette. */
    public static final int MOVE = PhoneColorSpec.MOVE;
    public static final int EXERCISE = PhoneColorSpec.EXERCISE;
    public static final int STAND = PhoneColorSpec.STAND;
    public static final int YELLOW = PhoneColorSpec.YELLOW;
    public static final int ORANGE = PhoneColorSpec.ORANGE;
    public static final int RED = PhoneColorSpec.RED;
    public static final int GREEN = PhoneColorSpec.GREEN;

    /** Deep tinted fills that carry a bright label of the matching accent. */
    public static final int FILL_RUN = PhoneColorSpec.FILL_RUN;
    public static final int FILL_WALK = PhoneColorSpec.FILL_WALK;
    public static final int FILL_REST = PhoneColorSpec.FILL_REST;
    public static final int FILL_DANGER = PhoneColorSpec.FILL_DANGER;
    public static final int FILL_SELECTED = PhoneColorSpec.FILL_SELECTED;

    public static final int SLEEP_DEEP = PhoneColorSpec.SLEEP_DEEP;
    public static final int SLEEP_LIGHT = PhoneColorSpec.SLEEP_LIGHT;
    public static final int SLEEP_REM = PhoneColorSpec.SLEEP_REM;
    public static final int SLEEP_AWAKE = PhoneColorSpec.SLEEP_AWAKE;
}
