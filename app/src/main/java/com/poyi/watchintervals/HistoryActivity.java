package com.poyi.watchintervals;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Calendar;

public class HistoryActivity extends WatchActivity {
    private WorkoutRecord selected;
    private SwipeTracker swipeTracker;
    private final WatchInteractionPolicy.ConfirmationGate deleteConfirmationGate =
            new WatchInteractionPolicy.ConfirmationGate();
    private View detailScrim;
    private LinearLayout deleteConfirmation;
    private TextView deleteAction;
    private TextView deleteCancel;
    private ScrollView detailScroll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        swipeTracker = new SwipeTracker(this, new SwipeTracker.Listener() {
            @Override public void onSwipeRight() { handleHistorySwipe(true); }
            @Override public void onSwipeLeft() { handleHistorySwipe(false); }
        });
        String recordId=getIntent().getStringExtra("record_id");
        WorkoutRecord record=recordId==null?null:HistoryStore.find(this,recordId);
        if(record!=null)showDetail(record);else showList();
    }

    private void showList() {
        selected = null;
        deleteConfirmationGate.cancel();
        LinearLayout root = base();
        root.addView(header("训练历史", this::finish));
        List<WorkoutRecord> records = HistoryStore.load(this);
        TextView summary = Ui.bold(this, records.isEmpty() ? "还没有训练记录" : records.size() + " 次训练", Ui.LABEL, records.isEmpty() ? Ui.MUTED : Ui.RED);
        root.addView(summary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 26)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        if (records.isEmpty()) {
            LinearLayout empty = Ui.card(this); empty.setGravity(Gravity.CENTER); empty.setOrientation(LinearLayout.VERTICAL);
            empty.addView(Ui.workoutGlyph(this, Ui.MUTED), new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
            TextView emptyTitle = Ui.bold(this, "完成一次训练后会显示在这里", 15, Ui.WHITE); emptyTitle.setGravity(Gravity.CENTER);
            empty.addView(emptyTitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 34)));
            TextView emptyHint = Ui.text(this, "距离、配速、心率与轨迹都会保存", Ui.CAPTION, Ui.MUTED); emptyHint.setGravity(Gravity.CENTER);
            empty.addView(emptyHint, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 150));
            emptyParams.topMargin = Ui.dp(this, 22); list.addView(empty, emptyParams);
        }
        String lastDay = null;
        for (WorkoutRecord record : records) {
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                    .format(new Date(record.startedAt));
            if (!day.equals(lastDay)) {
                TextView group = Ui.bold(this, dayLabel(record.startedAt), 14, Ui.MUTED);
                group.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                list.addView(group, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
                lastDay = day;
            }
            list.addView(recordRow(record));
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    /** Distance leads each entry — the figure a runner scans a history list by — with the
     *  start time as quiet metadata instead of the headline. */
    private View recordRow(WorkoutRecord record) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        row.setBackground(Ui.background(this, Ui.PANEL, Ui.RADIUS_CARD));
        row.addView(Ui.workoutGlyph(this, Ui.LIME), new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = Ui.numeral(this, Format.distance(record.distanceMeters), 21, Ui.LIME);
        headline.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        TextView when = Ui.text(this, new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(record.startedAt)), Ui.LABEL, Ui.MUTED);
        headline.addView(when, new LinearLayout.LayoutParams(-2, -2));
        copy.addView(headline, new LinearLayout.LayoutParams(-1, Ui.dp(this, 27)));
        TextView data = Ui.text(this, runnerMeta(record), Ui.CAPTION, Ui.MUTED);
        copy.addView(data, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -1, 1); copyParams.leftMargin = Ui.dp(this, 9); row.addView(copy, copyParams);
        row.setClickable(true); row.setFocusable(true); row.setOnClickListener(v -> showDetail(record));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.LIST_ROW));
        params.bottomMargin = Ui.dp(this, 7); row.setLayoutParams(params);
        return row;
    }

    private void showDetail(WorkoutRecord summaryRecord) {
        // The list rows carry index summaries only. The derived cards — splits, best pace,
        // heart-rate range, climb — and the route map are recomputed from the sample files, so
        // opening a record from the list used to show a bare summary while the same record
        // opened from the home pager (record_id intent -> find) showed everything.
        WorkoutRecord loaded = HistoryStore.find(this, summaryRecord.id);
        WorkoutRecord record = loaded != null ? loaded : summaryRecord;
        selected = record;
        deleteConfirmationGate.cancel();
        LinearLayout page = base();
        page.addView(header("训练详情", this::showList));
        LinearLayout identity = new LinearLayout(this); identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(Ui.workoutGlyph(this, Ui.LIME), new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        LinearLayout identityCopy = new LinearLayout(this); identityCopy.setOrientation(LinearLayout.VERTICAL);
        TextView plan = Ui.bold(this, record.planName.isEmpty() ? "户外训练" : record.planName, 19, Ui.WHITE);
        identityCopy.addView(plan, new LinearLayout.LayoutParams(-1, Ui.dp(this, 26)));
        TextView date = Ui.text(this, new SimpleDateFormat("yyyy年MM月dd日  HH:mm", Locale.CHINA).format(new Date(record.startedAt)), Ui.CAPTION, Ui.MUTED);
        identityCopy.addView(date, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));
        LinearLayout.LayoutParams identityCopyParams = new LinearLayout.LayoutParams(0, -1, 1); identityCopyParams.leftMargin = Ui.dp(this, 10); identity.addView(identityCopy, identityCopyParams);
        page.addView(identity, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));

        // Same colour semantics as the live training page, so the summary reads as a continuation
        // of the workout rather than an unrelated report.
        LinearLayout metricsCard = Ui.card(this); LinearLayout metrics = new LinearLayout(this);
        addMetric(metrics, "距离", Format.distance(record.distanceMeters), Ui.LIME);
        addMetric(metrics, "用时", Format.duration(record.durationMs), Ui.WHITE);
        addMetric(metrics, "步数", record.steps + "", Ui.YELLOW);
        metricsCard.addView(metrics, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));
        LinearLayout metrics2 = new LinearLayout(this);
        addMetric(metrics2, "平均心率", record.averageHeartRate > 0 ? record.averageHeartRate + " bpm" : "--",
                record.averageHeartRate > 0 ? Ui.RED : Ui.MUTED);
        double paceSecondsPerKm = record.distanceMeters > 0 ? record.durationMs / record.distanceMeters : 0;
        addMetric(metrics2, "平均配速", paceSecondsPerKm > 0 ? SpeedFusion.formatPace(paceSecondsPerKm) : "--",
                paceSecondsPerKm > 0 ? Ui.CYAN : Ui.MUTED);
        if(record.steps>0&&record.durationMs>=30_000)addMetric(metrics2,"平均步频",Math.round(record.steps*60_000d/record.durationMs)+" spm",Ui.WHITE);
        metricsCard.addView(metrics2, new LinearLayout.LayoutParams(-1, Ui.dp(this, 58)));page.addView(metricsCard);

        java.util.ArrayList<Integer> validHeartValues = new java.util.ArrayList<>();
        for (Integer value : record.heartValues) if (value != null && value >= 25 && value <= 240) validHeartValues.add(value);
        if (validHeartValues.size() > 1) {
            LinearLayout heartCard = Ui.card(this);
            LinearLayout heartHeader = new LinearLayout(this); heartHeader.setGravity(Gravity.CENTER_VERTICAL);
            heartHeader.addView(Ui.bold(this, "心率趋势", 16, Ui.RED), new LinearLayout.LayoutParams(0, Ui.dp(this, 28), 1));
            int heartMin = java.util.Collections.min(validHeartValues), heartMax = java.util.Collections.max(validHeartValues);
            TextView range = Ui.numeral(this, heartMin + "–" + heartMax, 14, Ui.WHITE); range.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            heartHeader.addView(range, new LinearLayout.LayoutParams(-2, Ui.dp(this, 28))); heartCard.addView(heartHeader);
            Ui.HeartTrace trace = new Ui.HeartTrace(this); trace.setSamples(validHeartValues);
            heartCard.addView(trace, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
            page.addView(heartCard, sectionParams());
        }

        WorkoutRouteView route = new WorkoutRouteView(this);
        route.setActive(true);
        double[] latitudes = new double[record.route.size()], longitudes = new double[record.route.size()];
        for (int index = 0; index < record.route.size(); index++) {
            latitudes[index] = record.route.get(index).getLatitude();
            longitudes[index] = record.route.get(index).getLongitude();
        }
        route.setRoute(latitudes, longitudes);
        if (record.route.isEmpty()) {
            route.setEmptyMessage("本记录未包含GPS轨迹");
        }
        TextView routeTitle=Ui.bold(this,"运动轨迹",17,Ui.WHITE);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,Ui.dp(this,36));titleParams.topMargin=Ui.dp(this,10);page.addView(routeTitle,titleParams);
        // OWW221's stock sports record uses a compact 164dp map module. Keeping the same height
        // prevents the basemap from swallowing the detail page and makes roads/trail read at the
        // same visual scale instead of stretching the map into a 230dp hero card.
        page.addView(route, new LinearLayout.LayoutParams(-1, Ui.dp(this, 164)));

        try {
            org.json.JSONObject json=record.toJson();
            if(json.has("bestPaceSecondsPerKm")){LinearLayout card=detailCard("配速表现");card.addView(detailLine("最佳瞬时配速",SpeedFusion.formatPace(json.optLong("bestPaceSecondsPerKm"))));page.addView(card,sectionParams());}
            org.json.JSONArray splits=json.optJSONArray("splits");if(splits!=null&&splits.length()>0){LinearLayout card=detailCard("分段");
                // The fastest kilometre gets the lime accent, the way every running log marks it.
                int fastest=-1;long best=Long.MAX_VALUE;for(int i=0;i<splits.length();i++){long paceSeconds=splits.getJSONObject(i).optLong("paceSecondsPerKm");if(paceSeconds>0&&paceSeconds<best){best=paceSeconds;fastest=i;}}
                for(int i=0;i<splits.length();i++){org.json.JSONObject split=splits.getJSONObject(i);card.addView(detailLine(split.optInt("index")+" 公里",Format.duration(split.optLong("durationMs"))+"  ·  "+SpeedFusion.formatPace(split.optLong("paceSecondsPerKm")),i==fastest&&splits.length()>1?Ui.LIME:Ui.WHITE));}page.addView(card,sectionParams());}
            if(json.has("heartRateRange")){org.json.JSONObject range=json.getJSONObject("heartRateRange");LinearLayout card=detailCard("心率");card.addView(detailLine("平均心率",record.averageHeartRate+" bpm"));card.addView(detailLine("实测范围",range.optInt("min")+"–"+range.optInt("max")+" bpm"));page.addView(card,sectionParams());}
            if(json.has("elevationGainMeters")){LinearLayout card=detailCard("海拔");card.addView(detailLine("累计爬升",Math.round(json.optDouble("elevationGainMeters"))+" m"));page.addView(card,sectionParams());}
            org.json.JSONArray stages=json.optJSONArray("stageResults");
            if(stages!=null&&stages.length()>0){
                page.addView(buildStageBreakdownCard(record, stages), sectionParams());
            }
        } catch(Exception ignored) {}
        TextView delete = Ui.iconAction(this, "删除本次记录", 15, Ui.RED, Ui.PANEL, Ui.Symbol.DELETE);
        deleteAction = delete;
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_PRIMARY));
        deleteParams.topMargin = Ui.dp(this, 12); page.addView(delete, deleteParams);
        delete.setOnClickListener(v -> showDeleteConfirmation());
        detailScroll = new ScrollView(this);
        detailScroll.setFillViewport(true);
        detailScroll.setVerticalScrollBarEnabled(false);
        detailScroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        FrameLayout shell = new FrameLayout(this);
        shell.addView(detailScroll, new FrameLayout.LayoutParams(-1, -1));
        detailScrim = new View(this);
        detailScrim.setBackgroundColor(Ui.SCRIM);
        detailScrim.setClickable(true);
        detailScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        detailScrim.setVisibility(View.GONE);
        detailScrim.setOnClickListener(v -> hideDeleteConfirmation());
        shell.addView(detailScrim, new FrameLayout.LayoutParams(-1, -1));
        deleteConfirmation = buildDeleteConfirmation(record);
        FrameLayout.LayoutParams confirmationParams =
                new FrameLayout.LayoutParams(-1, Ui.dp(this, 140), Gravity.BOTTOM);
        confirmationParams.leftMargin = Ui.dp(this, 12);
        confirmationParams.rightMargin = Ui.dp(this, 12);
        confirmationParams.bottomMargin = Ui.dp(this, 10);
        shell.addView(deleteConfirmation, confirmationParams);
        setContentView(shell);
    }

    private LinearLayout buildDeleteConfirmation(WorkoutRecord record) {
        LinearLayout panel = Ui.card(this);
        panel.setVisibility(View.GONE);
        panel.setFocusable(true);
        panel.setAccessibilityPaneTitle("删除记录确认");
        panel.addView(Ui.bold(this, "删除这次记录？", 17, Ui.WHITE),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 26)));
        panel.addView(Ui.text(this, "删除后无法恢复", Ui.CAPTION, Ui.MUTED),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        LinearLayout choices = new LinearLayout(this);
        TextView cancel = Ui.iconAction(this, "取消", 14, Ui.WHITE, Ui.PANEL_ACTIVE, Ui.Symbol.BACK);
        deleteCancel = cancel;
        TextView confirm = Ui.iconAction(this, "确认删除", 14, Ui.WHITE, Ui.RED, Ui.Symbol.DELETE);
        LinearLayout.LayoutParams cancelParams =
                new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        cancelParams.rightMargin = Ui.dp(this, 7);
        choices.addView(cancel, cancelParams);
        choices.addView(confirm, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        panel.addView(choices);
        cancel.setOnClickListener(v -> hideDeleteConfirmation());
        confirm.setOnClickListener(v -> {
            if (!deleteConfirmationGate.confirm()) return;
            HistoryStore.delete(this, record.id);
            showList();
        });
        return panel;
    }

    private void showDeleteConfirmation() {
        if (deleteConfirmation == null) return;
        deleteConfirmationGate.request();
        if (detailScroll != null) {
            detailScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        if (detailScrim != null) detailScrim.setVisibility(View.VISIBLE);
        deleteConfirmation.setVisibility(View.VISIBLE);
        if (deleteCancel != null) deleteCancel.requestFocus();
    }

    private void hideDeleteConfirmation() {
        deleteConfirmationGate.cancel();
        if (deleteConfirmation != null) deleteConfirmation.setVisibility(View.GONE);
        if (detailScrim != null) detailScrim.setVisibility(View.GONE);
        if (detailScroll != null) {
            detailScroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        if (deleteAction != null) deleteAction.requestFocus();
    }

    private void handleHistorySwipe(boolean swipedRight) {
        if (deleteConfirmationGate.isAwaitingConfirmation()) {
            if (swipedRight) hideDeleteConfirmation();
            return;
        }
        WatchInteractionPolicy.HistorySwipeAction action =
                WatchInteractionPolicy.historySwipeAction(swipedRight, selected != null);
        if (action == WatchInteractionPolicy.HistorySwipeAction.SHOW_LIST) showList();
        else if (action == WatchInteractionPolicy.HistorySwipeAction.FINISH) finish();
    }

    /** Duration, pace and heart rate — the line a runner scans a log by. Steps only stand in
     *  when a session has no distance to pace against. */
    private String runnerMeta(WorkoutRecord record) {
        StringBuilder meta = new StringBuilder(Format.duration(record.durationMs));
        if (record.distanceMeters > 0) meta.append(" · ").append(SpeedFusion.formatPace(record.durationMs / record.distanceMeters));
        else meta.append(" · ").append(record.steps).append(" 步");
        if (record.averageHeartRate > 0) meta.append(" · ").append(record.averageHeartRate).append(" bpm");
        return meta.toString();
    }

    private View buildStageBreakdownCard(WorkoutRecord record, org.json.JSONArray stages) {
        LinearLayout card = Ui.card(this);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.bold(this, "间歇阶段明细", 16, Ui.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 30), 1));
        TextView count = Ui.text(this, "共 " + stages.length() + " 阶段", Ui.CAPTION, Ui.MUTED);
        header.addView(count, new LinearLayout.LayoutParams(-2, Ui.dp(this, 30)));
        card.addView(header);

        long prevCompletedAtMs = 0;
        double prevDistMeters = 0;

        for (int i = 0; i < stages.length(); i++) {
            org.json.JSONObject stage = stages.optJSONObject(i);
            if (stage == null) continue;
            int index = stage.optInt("index", i + 1);
            String name = stage.optString("name", "阶段");
            String unit = stage.optString("unit", "");
            long target = stage.optLong("target", 0);
            long completedAtMs = stage.optLong("completedAtMs", 0);
            double totalDist = stage.optDouble("totalDistanceMeters", 0);

            long stageDurationMs = stage.optLong("stageDurationMs", 0);
            double stageDistMeters = stage.optDouble("stageDistanceMeters", 0);
            int stageHeart = stage.optInt("stageAvgHeartRate", 0);
            long stagePace = stage.optLong("stagePaceSecondsPerKm", 0);

            if (stageDurationMs <= 0) {
                stageDurationMs = Math.max(0, completedAtMs - prevCompletedAtMs);
            }
            if (stageDistMeters <= 0) {
                stageDistMeters = Math.max(0, totalDist - prevDistMeters);
            }
            if (stagePace <= 0 && stageDistMeters >= 5 && stageDurationMs > 0) {
                stagePace = Math.round(stageDurationMs / 1000d * 1000d / stageDistMeters);
            }

            if (stageHeart <= 0 && !record.heartTimes.isEmpty()) {
                long segStart = record.startedAt + prevCompletedAtMs;
                long segEnd = record.startedAt + completedAtMs;
                int hSum = 0, hCount = 0;
                for (int k = 0; k < Math.min(record.heartTimes.size(), record.heartValues.size()); k++) {
                    long t = record.heartTimes.get(k);
                    if (t >= segStart && t <= segEnd) {
                        int val = record.heartValues.get(k);
                        if (val >= 40 && val <= 220) {
                            hSum += val;
                            hCount++;
                        }
                    }
                }
                if (hCount > 0) stageHeart = Math.round((float) hSum / hCount);
            }

            prevCompletedAtMs = completedAtMs;
            prevDistMeters = totalDist;

            String targetDesc = "";
            if (target > 0) {
                if ("DISTANCE".equalsIgnoreCase(unit)) {
                    targetDesc = target >= 1000 ? (target / 1000 + "km") : (target + "m");
                } else if ("TIME".equalsIgnoreCase(unit)) {
                    targetDesc = target >= 60 ? (target / 60 + "分") : (target + "秒");
                }
            }

            boolean isRun = name.contains("跑");
            int accentColor = isRun ? Ui.LIME : (name.contains("走") ? Ui.YELLOW : Ui.CYAN);

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

            LinearLayout row1 = new LinearLayout(this);
            row1.setGravity(Gravity.CENTER_VERTICAL);
            String label = index + ". " + name + (targetDesc.isEmpty() ? "" : " · " + targetDesc);
            TextView left1 = Ui.bold(this, label, 14, accentColor);
            row1.addView(left1, new LinearLayout.LayoutParams(0, -2, 1));

            String distPaceStr = (stageDistMeters >= 1000
                    ? String.format(Locale.CHINA, "%.2fkm", stageDistMeters / 1000d)
                    : Math.round(stageDistMeters) + "m")
                    + (stagePace > 0 ? "  " + SpeedFusion.formatPace(stagePace) : "");
            TextView right1 = Ui.numeral(this, distPaceStr, 13, Ui.WHITE);
            right1.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row1.addView(right1, new LinearLayout.LayoutParams(-2, -2));
            item.addView(row1);

            LinearLayout row2 = new LinearLayout(this);
            row2.setGravity(Gravity.CENTER_VERTICAL);
            TextView left2 = Ui.text(this, "用时 " + Format.duration(stageDurationMs), 12, Ui.MUTED);
            row2.addView(left2, new LinearLayout.LayoutParams(0, -2, 1));

            if (stageHeart > 0) {
                TextView right2 = Ui.numeral(this, stageHeart + " bpm", 12, Ui.RED);
                right2.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                row2.addView(right2, new LinearLayout.LayoutParams(-2, -2));
            }
            item.addView(row2);

            if (i < stages.length() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0x15FFFFFF);
                LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 1));
                divParams.topMargin = Ui.dp(this, 5);
                item.addView(divider, divParams);
            }

            card.addView(item);
        }
        return card;
    }

    private LinearLayout detailCard(String title){LinearLayout card=Ui.card(this);card.addView(Ui.bold(this,title,16,Ui.WHITE),new LinearLayout.LayoutParams(-1,Ui.dp(this,30)));return card;}
    private View detailLine(String label,String value){return detailLine(label,value,Ui.WHITE);}
    // Value wraps, label takes the rest: a fixed 180dp value column squeezed the label to ~40dp
    // on the 378px canvas and every two-digit split read as "10 公…".
    private View detailLine(String label,String value,int valueColor){LinearLayout row=new LinearLayout(this);TextView left=Ui.text(this,label,13,Ui.MUTED);TextView right=Ui.bold(this,value,14,valueColor);right.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.addView(left,new LinearLayout.LayoutParams(0,Ui.dp(this,34),1));row.addView(right,new LinearLayout.LayoutParams(-2,Ui.dp(this,34)));return row;}
    private LinearLayout.LayoutParams sectionParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=Ui.dp(this,8);return p;}

    private LinearLayout base() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 6), Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 10));
        root.setBackgroundColor(Ui.BLACK); return root;
    }

    private View header(String titleText, Runnable backAction) {
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.backButton(this); back.setOnClickListener(v -> backAction.run());
        TextView title = Ui.bold(this, titleText, 19, Ui.WHITE);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        backParams.rightMargin = Ui.dp(this, 8);
        header.addView(back, backParams);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1)); return header;
    }

    private void addMetric(LinearLayout row, String label, String value, int color) {
        LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
        TextView caption = Ui.bold(this, label, Ui.CAPTION, color); caption.setGravity(Gravity.CENTER);
        TextView data = Ui.numeral(this, value, 18, color); data.setGravity(Gravity.CENTER);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));
        cell.addView(data, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
        row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private String dayLabel(long timestamp) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        if (sameDay(target, today)) return "今天";
        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(target, today)) return "昨天";
        return new SimpleDateFormat("M月d日  EEEE", Locale.CHINA)
                .format(new Date(timestamp));
    }
    private boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.ERA) == second.get(Calendar.ERA)
                && first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    @Override public void onBackPressed() {
        if (deleteConfirmationGate.isAwaitingConfirmation()) hideDeleteConfirmation();
        else if (selected != null) showList();
        else super.onBackPressed();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (swipeTracker != null) swipeTracker.observe(event);
        return super.dispatchTouchEvent(event);
    }
}
