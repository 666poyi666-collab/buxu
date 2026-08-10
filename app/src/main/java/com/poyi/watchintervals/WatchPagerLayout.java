package com.poyi.watchintervals;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import java.util.IdentityHashMap;

/** Pixel-following watch pager modeled after the system sports TossViewPager behavior. */
final class WatchPagerLayout extends ViewGroup {
    /** Fired when the user drags right past the first page and releases — the watch-wide
     *  "swipe right to leave" gesture. Only screens that register this can be exited that way;
     *  the workout pager deliberately does not, so a sweaty mis-swipe cannot end a session. */
    interface OnExitListener { void onSwipeExit(); }

    /** Heavy pages (notably the route) only become active after the settle animation is over. */
    interface OnPageSettledListener { void onPageSettled(int item); }

    /** The stock sports pager uses a quintic ease-out: decisive at release, soft at the stop. */
    private static final Interpolator QUINTIC_EASE_OUT = input -> {
        float shifted = input - 1f;
        return shifted * shifted * shifted * shifted * shifted + 1f;
    };
    /** Drag past the edge moves the content at one third speed — enough to feel the boundary. */
    private static final float EDGE_DAMPING = 3f;
    /** Fraction of the page width the RAW finger travel past the edge must cover to exit. */
    private static final float EXIT_FRACTION = 0.22f;
    /** A pager class name TalkBack already understands, without taking a ViewPager dependency. */
    private static final String ACCESSIBILITY_CLASS_NAME = "androidx.viewpager.widget.ViewPager";

    private final Scroller scroller;
    private final int touchSlop, minimumVelocity, maximumVelocity;
    private VelocityTracker velocity;
    private long lastVelocityEventTime = Long.MIN_VALUE;
    private int lastVelocityAction = -1;
    private float downX, downY, lastX;
    /** Undamped finger-tracked scroll position. Damping is applied to the SHOWN value only;
     *  damping the accumulator itself compounded per event and froze the edge almost solid. */
    private float virtualScroll;
    private boolean horizontalDrag;
    private boolean touchActive;
    private boolean settling;
    private boolean staticPageCachingEnabled;
    private boolean pageIndicatorEnabled;
    private int currentItem;
    private int gestureStartItem;
    private OnExitListener exitListener;
    private OnPageSettledListener pageSettledListener;
    /** Preserve each page's own accessibility choice while it is the visible page. */
    private final IdentityHashMap<View, Integer> pageAccessibilityImportance =
            new IdentityHashMap<>();
    private boolean pendingAccessibilityPageChange;
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable warmStaticPageLayers = () -> {
        // buildLayer() may synchronously rasterize a full 378 x 496 page. Never let a delayed
        // warm-up land inside a finger-following or settle frame; the terminal path schedules it
        // again once motion is over.
        if (!staticPageCachingEnabled || !isAttachedToWindow() || isInMotion()) return;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            boolean nearby = Math.abs(index - currentItem) <= 1;
            int desired = nearby ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (child.getLayerType() == desired) continue;
            child.setLayerType(desired, null);
            if (nearby && child.getWidth() > 0) child.buildLayer();
        }
    };

    WatchPagerLayout(Context context) {
        super(context);
        scroller = new Scroller(context, QUINTIC_EASE_OUT);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledPagingTouchSlop();
        int systemPagerVelocity = Math.round(400f * getResources().getDisplayMetrics().density);
        minimumVelocity = Math.max(config.getScaledMinimumFlingVelocity(), systemPagerVelocity);
        maximumVelocity = config.getScaledMaximumFlingVelocity();
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setMotionEventSplittingEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    void setOnExitListener(OnExitListener listener) { exitListener = listener; }
    void setOnPageSettledListener(OnPageSettledListener listener) { pageSettledListener = listener; }

    /** Draws one fixed, finger-following indicator instead of one dot group moving with each page. */
    void setPageIndicatorEnabled(boolean enabled) {
        pageIndicatorEnabled = enabled;
        for (int index = 0; index < getChildCount(); index++) {
            setEmbeddedDotsVisibility(getChildAt(index), enabled ? INVISIBLE : VISIBLE);
        }
        invalidate();
    }

    private void setEmbeddedDotsVisibility(View view, int visibility) {
        if (view instanceof Ui.PagerDots) {
            view.setVisibility(visibility);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEmbeddedDotsVisibility(group.getChildAt(index), visibility);
        }
    }

    @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        pageAccessibilityImportance.put(child, child.getImportantForAccessibility());
        updatePageAccessibility();
    }

    @Override public void onViewRemoved(View child) {
        pageAccessibilityImportance.remove(child);
        super.onViewRemoved(child);
        currentItem = clampPage(currentItem);
        updatePageAccessibility();
    }

    /**
     * Off-screen pages are still laid out beside the viewport, so TalkBack would otherwise walk
     * into controls the user cannot see. Keep only the selected page's subtree exposed and
     * restore that page's original importance instead of blindly forcing its root focusable.
     */
    private void updatePageAccessibility() {
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            Integer original = pageAccessibilityImportance.get(child);
            int desired = index == currentItem
                    ? (original == null ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : original)
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
            if (child.getImportantForAccessibility() != desired) {
                child.setImportantForAccessibility(desired);
            }
        }
    }

    private String accessibilityPageStatus() {
        int count = getChildCount();
        if (count == 0) return "";
        return "第" + (currentItem + 1) + "页，共" + count + "页";
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        int count = getChildCount();
        info.setClassName(ACCESSIBILITY_CLASS_NAME);
        info.setScrollable(count > 1);
        info.setScreenReaderFocusable(true);
        if (count > 0) {
            info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(
                    1, count, false,
                    AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE));
            info.setStateDescription(accessibilityPageStatus());
        }
        if (currentItem > 0) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT);
        }
        if (currentItem + 1 < count) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT);
        }
    }

    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        int count = getChildCount();
        event.setClassName(ACCESSIBILITY_CLASS_NAME);
        event.setScrollable(count > 1);
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED && count > 0) {
            event.setItemCount(count);
            event.setFromIndex(currentItem);
            event.setToIndex(currentItem);
            event.setScrollX(currentItem);
            event.setMaxScrollX(count - 1);
            event.getText().add(accessibilityPageStatus());
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if ((action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId())
                && currentItem + 1 < getChildCount()) {
            setCurrentItem(currentItem + 1, true);
            return true;
        }
        if ((action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
                && currentItem > 0) {
            setCurrentItem(currentItem - 1, true);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void dispatchPageChangedForAccessibility() {
        if (!pendingAccessibilityPageChange) return;
        pendingAccessibilityPageChange = false;
        updatePageAccessibility();
        if (isAttachedToWindow()) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
    }

    /**
     * Enables one-time idle prewarming for small, static pagers such as the three-screen home.
     * Live workout pages must leave this disabled because their one-second updates dirty a whole
     * screen texture and make the next swipe more expensive.
     */
    void setStaticPageCachingEnabled(boolean enabled) {
        if (staticPageCachingEnabled == enabled) return;
        staticPageCachingEnabled = enabled;
        removeCallbacks(warmStaticPageLayers);
        if (enabled) scheduleStaticLayerWarmup(120L);
        else clearPageLayers();
    }

    private void scheduleStaticLayerWarmup(long delayMillis) {
        if (!staticPageCachingEnabled) return;
        removeCallbacks(warmStaticPageLayers);
        postDelayed(warmStaticPageLayers, delayMillis);
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // A vertical ScrollView may keep its vertical gesture; no child may permanently take the
        // horizontal stream from the watch-wide pager.
        super.requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(warmStaticPageLayers);
            boolean takeOverSettle = hasMeaningfulSettleRemaining();
            if (takeOverSettle) stopSettlingAtCurrentPosition();
            else finishNearlyCompleteSettle();
            downX = lastX = event.getX();
            downY = event.getY();
            virtualScroll = getScrollX();
            gestureStartItem = nearestPage();
            horizontalDrag = takeOverSettle;
            resetVelocity(event);
            if (takeOverSettle) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return false;
        }
        addVelocity(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (dx > touchSlop && dx > dy * 2f) {
                horizontalDrag = true;
                // Preserve downX as lastX: the first intercepted MOVE must apply the full
                // displacement. Resetting lastX here made short reverse drags snap back.
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (dy > touchSlop && dy > dx) {
                recycleVelocity();
                return false;
            }
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && !horizontalDrag) {
            recycleVelocity();
            scheduleStaticLayerWarmup(120L);
        }
        return horizontalDrag;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            removeCallbacks(warmStaticPageLayers);
            stopSettlingAtCurrentPosition();
            downX = lastX = event.getX();
            downY = event.getY();
            virtualScroll = getScrollX();
            gestureStartItem = nearestPage();
            horizontalDrag = false;
            touchActive = true;
            resetVelocity(event);
            return true;
        }
        addVelocity(event);
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                touchActive = true;
                float x = event.getX();
                float delta = lastX - x;
                lastX = x;
                int limit = Math.max(0, (getChildCount() - 1) * getWidth());
                // Track the raw finger position; damp only what is drawn. Past either end the page
                // keeps following at reduced speed, the native cue that there is no further page.
                virtualScroll += delta;
                float shown;
                if (virtualScroll < 0) shown = virtualScroll / EDGE_DAMPING;
                else if (virtualScroll > limit) shown = limit + (virtualScroll - limit) / EDGE_DAMPING;
                else shown = virtualScroll;
                scrollTo(Math.round(shown), 0);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vx = 0f;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000, maximumVelocity);
                    vx = velocity.getXVelocity();
                }
                if (virtualScroll < 0 && getScrollX() <= 0) {
                    boolean exitCommitted = action != MotionEvent.ACTION_CANCEL
                            && (-virtualScroll >= getWidth() * EXIT_FRACTION
                            || vx >= minimumVelocity);
                    recycleVelocity();
                    horizontalDrag = false;
                    touchActive = false;
                    virtualScroll = 0;
                    if (exitCommitted && exitListener != null) {
                        exitListener.onSwipeExit();
                        return true;
                    }
                    setCurrentItem(0, true);
                    return true;
                }

                float travel = event.getX() - downX;
                if (action == MotionEvent.ACTION_UP && Math.abs(travel) <= touchSlop
                        && Math.abs(event.getY() - downY) <= touchSlop) {
                    performClick();
                }
                float commitDistance = Math.max(touchSlop * 2f, getWidth() * 0.16f);
                int page;
                if (action != MotionEvent.ACTION_CANCEL && Math.abs(travel) >= commitDistance) {
                    page = travel < 0 ? gestureStartItem + 1 : gestureStartItem - 1;
                } else if (action != MotionEvent.ACTION_CANCEL
                        && Math.abs(vx) >= minimumVelocity) {
                    page = vx < 0 ? gestureStartItem + 1 : gestureStartItem - 1;
                } else {
                    page = nearestPage();
                }
                setCurrentItem(clampPage(page), true);
                recycleVelocity();
                horizontalDrag = false;
                touchActive = false;
                return true;
            }
            default:
                return true;
        }
    }

    private void resetVelocity(MotionEvent event) {
        recycleVelocity();
        velocity = VelocityTracker.obtain();
        lastVelocityEventTime = Long.MIN_VALUE;
        lastVelocityAction = -1;
        addVelocity(event);
    }

    private void addVelocity(MotionEvent event) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        // The MOVE that flips interception is delivered to both methods. Do not double-count it.
        if (event.getEventTime() == lastVelocityEventTime
                && event.getActionMasked() == lastVelocityAction) return;
        velocity.addMovement(event);
        lastVelocityEventTime = event.getEventTime();
        lastVelocityAction = event.getActionMasked();
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        lastVelocityEventTime = Long.MIN_VALUE;
        lastVelocityAction = -1;
    }

    private int nearestPage() {
        if (getWidth() <= 0) return clampPage(currentItem);
        return clampPage(Math.round(getScrollX() / (float) getWidth()));
    }

    private int clampPage(int item) {
        return Math.max(0, Math.min(getChildCount() - 1, item));
    }

    private void stopSettlingAtCurrentPosition() {
        if (scroller.isFinished()) return;
        scroller.computeScrollOffset();
        int currentX = scroller.getCurrX();
        scroller.abortAnimation();
        scrollTo(currentX, 0);
        settling = false;
    }

    private boolean hasMeaningfulSettleRemaining() {
        if (scroller.isFinished()) return false;
        scroller.computeScrollOffset();
        return Math.abs(scroller.getFinalX() - scroller.getCurrX())
                > Ui.dp(getContext(), 2f);
    }

    private void finishNearlyCompleteSettle() {
        if (scroller.isFinished()) return;
        scroller.abortAnimation();
        finishSettle();
    }

    void setCurrentItem(int item, boolean smooth) {
        int target = clampPage(item);
        boolean pageChanged = target != currentItem;
        currentItem = target;
        if (pageChanged) {
            pendingAccessibilityPageChange = true;
            updatePageAccessibility();
        }
        int destination = currentItem * getWidth();
        if (!smooth || getWidth() == 0) {
            scroller.abortAnimation();
            settling = false;
            scrollTo(destination, 0);
            postInvalidateOnAnimation();
            if (pageSettledListener != null && getWidth() > 0) {
                pageSettledListener.onPageSettled(currentItem);
            }
            dispatchPageChangedForAccessibility();
            scheduleStaticLayerWarmup(120L);
            return;
        }
        int dx = destination - getScrollX();
        if (dx == 0) {
            // A full-width finger drag can arrive exactly on the target pixel without ever
            // starting Scroller. The page still changed and heavy-page lifecycle listeners must
            // be told (route activation depends on this callback).
            finishSettle(pageChanged);
            return;
        }
        float pageFraction = Math.min(1f, Math.abs(dx) / (float) Math.max(1, getWidth()));
        int duration = Math.round(210f + 57f * pageFraction);
        settling = true;
        scroller.startScroll(getScrollX(), 0, dx, 0, duration);
        if (pageChanged) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        postInvalidateOnAnimation();
    }

    int getCurrentItem() { return currentItem; }
    boolean isInMotion() {
        return touchActive || horizontalDrag || settling || !scroller.isFinished();
    }

    private void finishSettle() { finishSettle(false); }

    private void finishSettle(boolean forceNotify) {
        if (getWidth() > 0) scrollTo(currentItem * getWidth(), 0);
        boolean notify = settling || forceNotify;
        settling = false;
        if (notify && pageSettledListener != null) pageSettledListener.onPageSettled(currentItem);
        dispatchPageChangedForAccessibility();
        // Build a newly adjacent page after motion stops, never in ACTION_UP or the settle frame.
        scheduleStaticLayerWarmup(45L);
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), scroller.getCurrY());
            if (!scroller.isFinished()) postInvalidateOnAnimation();
            else finishSettle();
        } else if (settling) {
            finishSettle();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int index = 0; index < getChildCount(); index++) {
            getChildAt(index).measure(childWidth, childHeight);
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            child.layout(index * width, 0, (index + 1) * width, bottom - top);
        }
        scheduleStaticLayerWarmup(120L);
        if (!horizontalDrag && scroller.isFinished()) scrollTo(currentItem * width, 0);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!pageIndicatorEnabled || getChildCount() <= 1 || getWidth() <= 0) return;
        float progress = Math.max(0f, Math.min(getChildCount() - 1f,
                getScrollX() / (float) getWidth()));
        float spacing = Ui.dp(getContext(), 8.5f);
        float radius = Ui.dp(getContext(), 2.25f);
        // dispatchDraw's canvas follows scrollX; offset by it so the indicator stays on glass.
        float centerX = getScrollX() + getWidth() / 2f;
        float centerY = getHeight() - Ui.dp(getContext(), 9.5f);
        float startX = centerX - (getChildCount() - 1) * spacing / 2f;
        indicatorPaint.setColor(Ui.LINE);
        for (int index = 0; index < getChildCount(); index++) {
            canvas.drawCircle(startX + index * spacing, centerY, radius * .72f, indicatorPaint);
        }
        float between = Math.abs(progress - Math.round(progress));
        float stretch = radius * (1f + .85f * (float) Math.sin(Math.PI * between));
        float activeX = startX + progress * spacing;
        indicatorPaint.setColor(Ui.WHITE);
        canvas.drawRoundRect(activeX - stretch, centerY - radius,
                activeX + stretch, centerY + radius, radius, radius, indicatorPaint);
    }

    private void clearPageLayers() {
        for (int index = 0; index < getChildCount(); index++) {
            getChildAt(index).setLayerType(LAYER_TYPE_NONE, null);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleStaticLayerWarmup(120L);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(warmStaticPageLayers);
        recycleVelocity();
        touchActive = false;
        clearPageLayers();
        super.onDetachedFromWindow();
    }
}
