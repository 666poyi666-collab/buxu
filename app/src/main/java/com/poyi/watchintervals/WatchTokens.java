package com.poyi.watchintervals;

import android.graphics.Color;

/**
 * 手表端设计令牌。
 *
 * OWW221 是一块 378 x 496 的 AMOLED 方形屏。canvas 用纯黑让这些像素物理不发光,既省电
 * 又得到无边框的整机观感;语义高饱和色只留给实时数据,绝不当装饰用。
 *
 * 这里集中所有色值、字号、间距与圆角,页面代码不得再出现裸色值。浅色底统一由
 * TINT_* 表达,保证同一语义色在各页面得到同一光学重量。
 */
final class WatchTokens {

    // ---- 画布与面板 -------------------------------------------------------
    static final int BLACK = Color.rgb(0, 0, 0);
    static final int PANEL = Color.rgb(23, 25, 28);
    static final int PANEL_ACTIVE = Color.rgb(35, 38, 43);
    static final int WHITE = Color.rgb(245, 247, 250);
    static final int MUTED = Color.rgb(144, 151, 161);
    static final int LINE = Color.rgb(44, 48, 54);

    // ---- 语义强调色 -------------------------------------------------------
    static final int LIME = Color.rgb(48, 209, 88);       // #30D158 活力荧光绿
    static final int YELLOW = Color.rgb(255, 214, 10);     // #FFD60A 明亮黄
    static final int CYAN = Color.rgb(56, 189, 248);      // #38BDF8 醒目青
    static final int AMBER = Color.rgb(255, 159, 10);     // #FF9F0A 暖橙
    static final int RED = Color.rgb(255, 51, 75);        // #FF334B 高对比亮红（心率专用）
    static final int GREEN = Color.rgb(52, 199, 89);      // #34C759 运动绿
    static final int BRAND = Color.rgb(255, 45, 85);      // #FF2D55 品牌活力红

    /**
     * 语义色的浅色底。用于芯片、徽章与强调面板的填充,
     * 让前景语义字色在深色画布上仍有足够对比。
     */
    static final int TINT_LIME = Color.rgb(29, 38, 20);
    static final int TINT_CYAN = Color.rgb(20, 38, 44);
    static final int TINT_AMBER = Color.rgb(45, 35, 20);

    /** 带语义描边的强调面板底。 */
    static final int PANEL_LIME_EDGE = Color.rgb(22, 29, 26);
    /** 轨迹画布底,刻意压暗以突出轨迹线本身。 */
    static final int PANEL_ROUTE = Color.rgb(15, 22, 23);
    /** 浮层遮罩:让确认层与页面内容分离,同时保留底色可读性。 */
    static final int SCRIM = Color.argb(190, 0, 0, 0);

    // ---- 字号阶梯 ---------------------------------------------------------
    static final float DISPLAY = 46f;
    static final float TITLE = 23f;
    static final float HEADLINE = 18f;
    static final float BODY = 14f;
    static final float LABEL = 12f;
    static final float CAPTION = 11f;
    /** 领跑的计时数字。 */
    static final float FIGURE_HERO = 52f;
    /** 训练页其余指标数字。 */
    static final float FIGURE = 38f;
    /** 跟随数字基线的单位标签。 */
    static final float FIGURE_LABEL = 14f;
    /** 阶段倒计时页三列实时指标的放大尺寸（抬腕一眼看清）。 */
    static final float STAGE_METRIC_FIGURE = 24f;
    static final float STAGE_METRIC_ROW = 42f;
    static final float STAGE_METRIC_GAP = 3f;

    // ---- 间距与形状 -------------------------------------------------------
    /** 页面左右边距。整机动效接近贴边。 */
    static final float PAGE_MARGIN = 14f;
    /** 卡片圆角。 */
    static final float RADIUS_CARD = 10f;
    /** 芯片与徽章圆角。 */
    static final float RADIUS_CHIP = 7f;
    /** 轨迹画布圆角。 */
    static final float RADIUS_ROUTE = 10f;

    // ---- 稳定控件尺寸 -----------------------------------------------------
    static final float HEADER_ICON = 34f;
    static final float ACTION_PRIMARY = 54f;
    static final float ACTION_SECONDARY = 40f;
    static final float ACTION_CONTROL = 54f;
    static final float LIST_ROW = 60f;

    private WatchTokens() {}
}
