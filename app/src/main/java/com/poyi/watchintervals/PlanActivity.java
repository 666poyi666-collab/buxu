package com.poyi.watchintervals;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Offline selector for the complete phone-authoritative plan library mirror. */
public class PlanActivity extends WatchActivity {
    private static final String STATE_DETAIL_PLAN_ID = "detail_plan_id";
    private static final String STATE_GROUP_ID = "group_id";

    private final PlanSelectionUiPolicy navigation = new PlanSelectionUiPolicy();
    private FrameLayout pageHost;
    private JSONObject library = new JSONObject();
    private Set<String> availablePlanIds = new HashSet<>();
    private String openGroupId = "";
    private boolean selectionInProgress;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            openGroupId = state.getString(STATE_GROUP_ID, "");
            String restoredPlanId = state.getString(STATE_DETAIL_PLAN_ID, "");
            if (!restoredPlanId.isEmpty()) navigation.openDetails(restoredPlanId);
        }
        pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(Ui.BLACK);
        setContentView(pageHost);
    }

    /**
     * The phone/cloud library can change while this Activity is merely covered. Rebuilding the
     * projection here prevents a stale plan card or a deleted detail from remaining actionable.
     */
    @Override protected void onResume() {
        super.onResume();
        reloadProjection();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (navigation.screen() == PlanSelectionUiPolicy.Screen.DETAIL) {
            state.putString(STATE_DETAIL_PLAN_ID, navigation.detailPlanId());
        }
        state.putString(STATE_GROUP_ID, openGroupId);
    }

    private void reloadProjection() {
        library = PlanLibraryStore.load(this);
        availablePlanIds = planIds(library);
        navigation.reconcile(availablePlanIds);
        if (!openGroupId.isEmpty() && !groupExists(openGroupId)) openGroupId = "";
        renderPage();
    }

    private void renderPage() {
        pageHost.removeAllViews();
        View page = navigation.screen() == PlanSelectionUiPolicy.Screen.DETAIL
                ? buildDetailPage(findPlan(navigation.detailPlanId()))
                : openGroupId.isEmpty() ? buildListPage() : buildGroupPage(openGroupId);
        pageHost.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private View buildListPage() {
        LinearLayout page = basePage();
        int planCount = availablePlanIds.size();
        int groupCount = library.optJSONArray("groups") == null
                ? 0 : library.optJSONArray("groups").length();
        page.addView(header("训练计划",
                planCount == 0 ? "等待手机同步"
                        : groupCount + " 个分组 · " + planCount + " 个安排",
                this::finish, "返回主页"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        if (planCount == 0) renderEmptyLibrary(content);
        else renderGroupIndex(content);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private View buildGroupPage(String groupId) {
        String groupName = groupId.equals("__ungrouped__")
                ? "未分组" : PlanLibraryStore.groupName(library, groupId);
        ArrayList<JSONObject> plans = plansForGroup(groupId);
        LinearLayout page = basePage();
        page.addView(header(groupName, plans.size() + " 个安排 · 离线可用",
                () -> { openGroupId = ""; renderPage(); }, "返回分组列表"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        if (plans.isEmpty()) {
            TextView empty = Ui.text(this, "这个分组还没有安排", 14, Ui.MUTED);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(this, 96)));
        } else {
            for (JSONObject plan : plans) content.addView(planCard(plan));
        }
        TextView source = Ui.text(this, "由手机或 ChatGPT 管理 · 自动同步",
                Ui.CAPTION, Ui.MUTED);
        source.setGravity(Gravity.CENTER);
        content.addView(source, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private View buildDetailPage(JSONObject plan) {
        // A malformed or concurrently deleted plan cannot leave a dead detail surface behind.
        if (plan == null) {
            navigation.showList();
            return buildListPage();
        }

        String groupName = PlanLibraryStore.groupName(library, plan.optString("groupId"));
        ArrayList<Stage> stages = decodeStages(plan);
        boolean selected = plan.optString("id").equals(library.optString("selectedPlanId"));
        LinearLayout page = basePage();
        page.addView(header("计划详情", groupName,
                () -> { navigation.showList(); renderPage(); }, "返回当前分组"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout identityCard = Ui.card(this);
        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(Ui.workoutGlyph(this, selected ? Ui.LIME : Ui.CYAN),
                new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.bold(this, plan.optString("name", "训练计划"), 19, Ui.WHITE);
        name.setSingleLine(false);
        name.setMaxLines(2);
        copy.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = Ui.text(this, summary(stages), Ui.CAPTION,
                selected ? Ui.LIME : Ui.MUTED);
        copy.addView(meta, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = Ui.dp(this, 10);
        identity.addView(copy, copyParams);
        if (selected) identity.addView(Ui.chip(this, "当前", Ui.LIME,
                Ui.TINT_LIME),
                new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 24)));
        identityCard.addView(identity, new LinearLayout.LayoutParams(-1, -2));

        TextView requirementLabel = Ui.bold(this, "训练要求", Ui.LABEL, Ui.MUTED);
        LinearLayout.LayoutParams requirementLabelParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 26));
        requirementLabelParams.topMargin = Ui.dp(this, 5);
        identityCard.addView(requirementLabel, requirementLabelParams);
        String requirement = plan.optString("requirement").trim();
        TextView requirementText = Ui.text(this,
                requirement.isEmpty() ? "按下方训练内容顺序完成。" : requirement,
                12, Ui.WHITE);
        requirementText.setSingleLine(false);
        requirementText.setMaxLines(5);
        identityCard.addView(requirementText, new LinearLayout.LayoutParams(-1, -2));
        identityCard.setContentDescription(plan.optString("name", "训练计划") + "，"
                + groupName + "，" + summary(stages)
                + (selected ? "，当前计划" : "") + "。训练要求："
                + requirementText.getText());
        content.addView(identityCard);

        TextView stageHeading = Ui.bold(this, "训练内容 · " + stages.size() + " 项",
                15, Ui.WHITE);
        LinearLayout.LayoutParams stageHeadingParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 38));
        stageHeadingParams.topMargin = Ui.dp(this, 6);
        content.addView(stageHeading, stageHeadingParams);
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            LinearLayout row = Ui.stageRow(this, index + 1, stage, Ui.PANEL);
            row.setContentDescription("第 " + (index + 1) + " 阶段，" + stage.name()
                    + "，" + stage.targetText());
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
            rowParams.bottomMargin = Ui.dp(this, 6);
            content.addView(row, rowParams);
        }
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView select = Ui.iconAction(this, "设为当前", 17, Ui.BLACK, Ui.LIME, Ui.Symbol.CHECK);
        select.setContentDescription("将“" + plan.optString("name", "训练计划")
                + "”设为当前训练计划");
        boolean canSelect = navigation.canSelect(availablePlanIds) && !stages.isEmpty();
        select.setEnabled(canSelect);
        select.setAlpha(canSelect ? 1f : .42f);
        select.setOnClickListener(v -> applySelection(select, plan.optString("id"),
                plan.optString("name", "训练计划")));
        LinearLayout.LayoutParams selectParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_PRIMARY));
        selectParams.topMargin = Ui.dp(this, 8);
        page.addView(select, selectParams);

        TextView hint = Ui.text(this, selected ? "当前正在使用 · 重新选择后返回主页" : "选择后主页立即更新",
                Ui.CAPTION, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        return page;
    }

    private void renderGroupIndex(LinearLayout target) {
        JSONArray groups = library.optJSONArray("groups");
        if (groups != null) for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group == null) continue;
            ArrayList<JSONObject> plans = plansForGroup(group.optString("id"));
            target.addView(groupCard(
                    group.optString("id"), group.optString("name", "未命名分组"), plans));
        }
        ArrayList<JSONObject> ungrouped = plansForGroup("__ungrouped__");
        if (!ungrouped.isEmpty()) {
            target.addView(groupCard("__ungrouped__", "未分组", ungrouped));
        }
        TextView source = Ui.text(this, "先选择分组，再选择当天安排",
                Ui.CAPTION, Ui.MUTED);
        source.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sourceParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 34));
        sourceParams.topMargin = Ui.dp(this, 4);
        target.addView(source, sourceParams);
    }

    private View groupCard(String groupId, String name, ArrayList<JSONObject> plans) {
        boolean containsSelected = false;
        String selectedName = "";
        String selectedId = library.optString("selectedPlanId");
        for (JSONObject plan : plans) {
            if (selectedId.equals(plan.optString("id"))) {
                containsSelected = true;
                selectedName = plan.optString("name");
                break;
            }
        }
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 8));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(52, 255, 255, 255)),
                Ui.outlinedBackground(this,
                        containsSelected ? Ui.PANEL_ACTIVE : Ui.PANEL,
                        containsSelected ? Ui.LIME : Ui.LINE, Ui.RADIUS_CARD),
                Ui.background(this, Color.WHITE, Ui.RADIUS_CARD)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.bold(this, name, 17,
                containsSelected ? Ui.LIME : Ui.WHITE);
        copy.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 26)));
        String detail = plans.size() + " 个安排";
        if (containsSelected && !selectedName.isEmpty()) detail += " · 当前 " + selectedName;
        TextView meta = Ui.text(this, detail, Ui.CAPTION, Ui.MUTED);
        copy.addView(meta, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        card.addView(copy, new LinearLayout.LayoutParams(0, -1, 1));

        TextView chevron = Ui.text(this, "", Ui.BODY, Ui.MUTED);
        Ui.setActionSymbol(this, chevron, Ui.Symbol.FORWARD, Ui.MUTED);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(this, 24), -1));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(name + "，" + detail + "，查看分组");
        Ui.pressable(card);
        card.setOnClickListener(v -> {
            openGroupId = groupId;
            renderPage();
        });
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 62));
        params.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(params);
        return card;
    }

    private ArrayList<JSONObject> plansForGroup(String groupId) {
        ArrayList<JSONObject> result = new ArrayList<>();
        JSONArray plans = library.optJSONArray("plans");
        if (plans == null) return result;
        boolean ungrouped = "__ungrouped__".equals(groupId);
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan == null) continue;
            String candidate = plan.optString("groupId");
            if (ungrouped ? candidate.isEmpty() || !groupExists(candidate)
                    : groupId.equals(candidate)) result.add(plan);
        }
        result.sort((first, second) -> Integer.compare(
                first.optInt("sortOrder", Integer.MAX_VALUE),
                second.optInt("sortOrder", Integer.MAX_VALUE)));
        return result;
    }

    private boolean groupExists(String groupId) {
        if ("__ungrouped__".equals(groupId)) return !plansForUnknownGroup().isEmpty();
        JSONArray groups = library.optJSONArray("groups");
        if (groups == null) return false;
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null && groupId.equals(group.optString("id"))) return true;
        }
        return false;
    }

    private ArrayList<JSONObject> plansForUnknownGroup() {
        ArrayList<JSONObject> result = new ArrayList<>();
        JSONArray plans = library.optJSONArray("plans");
        if (plans == null) return result;
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan == null) continue;
            String groupId = plan.optString("groupId");
            if (groupId.isEmpty() || !knownGroupId(groupId)) result.add(plan);
        }
        return result;
    }

    private boolean knownGroupId(String groupId) {
        JSONArray groups = library.optJSONArray("groups");
        if (groups == null) return false;
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null && groupId.equals(group.optString("id"))) return true;
        }
        return false;
    }

    private void renderLibrary(LinearLayout target) {
        JSONArray plans = library.optJSONArray("plans");
        JSONArray groups = library.optJSONArray("groups");
        if (plans == null) return;
        Set<String> renderedIds = new HashSet<>();

        if (groups != null) for (int groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
            JSONObject group = groups.optJSONObject(groupIndex);
            if (group == null) continue;
            String groupId = group.optString("id");
            ArrayList<JSONObject> matches = new ArrayList<>();
            for (int planIndex = 0; planIndex < plans.length(); planIndex++) {
                JSONObject plan = plans.optJSONObject(planIndex);
                if (plan != null && groupId.equals(plan.optString("groupId"))) matches.add(plan);
            }
            if (matches.isEmpty()) continue;
            addGroup(target, group.optString("name", "我的计划"), matches, renderedIds);
        }

        ArrayList<JSONObject> ungrouped = new ArrayList<>();
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan != null && !renderedIds.contains(plan.optString("id"))) ungrouped.add(plan);
        }
        if (!ungrouped.isEmpty()) addGroup(target, "我的计划", ungrouped, renderedIds);

        TextView source = Ui.text(this, "计划由手机端或云端管理并自动同步", Ui.CAPTION, Ui.MUTED);
        source.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sourceParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 30));
        sourceParams.topMargin = Ui.dp(this, 2);
        target.addView(source, sourceParams);
    }

    private void addGroup(LinearLayout target, String name, ArrayList<JSONObject> plans,
                          Set<String> renderedIds) {
        TextView heading = Ui.bold(this, name + "  ·  " + plans.size(), 14, Ui.CYAN);
        LinearLayout.LayoutParams headingParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 38));
        headingParams.topMargin = Ui.dp(this, 2);
        target.addView(heading, headingParams);
        for (JSONObject plan : plans) {
            renderedIds.add(plan.optString("id"));
            target.addView(planCard(plan));
        }
    }

    private View planCard(JSONObject plan) {
        String planId = plan.optString("id");
        String name = plan.optString("name", "训练计划");
        boolean selected = planId.equals(library.optString("selectedPlanId"));
        ArrayList<Stage> stages = decodeStages(plan);

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(52, 255, 255, 255)),
                Ui.outlinedBackground(this, selected ? Ui.PANEL_ACTIVE : Ui.PANEL,
                        selected ? Ui.LIME : Ui.LINE, 18),
                Ui.background(this, Color.WHITE, Ui.RADIUS_CARD)));
        card.addView(Ui.workoutGlyph(this, selected ? Ui.LIME : Ui.MUTED),
                new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.bold(this, name, 17, Ui.WHITE);
        title.setSingleLine(false);
        title.setMaxLines(2);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = Ui.text(this, summary(stages), Ui.CAPTION,
                selected ? Ui.LIME : Ui.MUTED);
        copy.addView(meta, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = Ui.dp(this, 9);
        card.addView(copy, copyParams);

        if (selected) card.addView(Ui.chip(this, "当前", Ui.LIME,
                Ui.TINT_LIME),
                new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 24)));
        TextView chevron = Ui.text(this, "", Ui.BODY, Ui.MUTED);
        Ui.setActionSymbol(this, chevron, Ui.Symbol.FORWARD, Ui.MUTED);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(this, 18), -1));

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("查看计划“" + name + "”，" + summary(stages)
                + (selected ? "，当前计划" : ""));
        Ui.pressable(card);
        card.setOnClickListener(v -> {
            navigation.openDetails(planId);
            renderPage();
        });
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.LIST_ROW));
        params.bottomMargin = Ui.dp(this, 7);
        card.setLayoutParams(params);
        return card;
    }

    private void renderEmptyLibrary(LinearLayout target) {
        LinearLayout empty = Ui.card(this);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.addView(Ui.workoutGlyph(this, Ui.MUTED),
                new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        TextView title = Ui.bold(this, "还没有训练计划", 17, Ui.WHITE);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));
        TextView message = Ui.text(this, "请在手机端添加计划并同步到手表", 12, Ui.MUTED);
        message.setGravity(Gravity.CENTER);
        message.setSingleLine(false);
        message.setMaxLines(2);
        empty.addView(message, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));

        TextView unavailable = Ui.iconAction(this, "暂无可选计划", 15, Ui.MUTED,
                Ui.PANEL_ACTIVE, Ui.Symbol.LIST);
        unavailable.setEnabled(false);
        unavailable.setAlpha(.42f);
        unavailable.setContentDescription("暂无可选计划，请先在手机端添加并同步");
        LinearLayout.LayoutParams unavailableParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        unavailableParams.topMargin = Ui.dp(this, 8);
        empty.addView(unavailable, unavailableParams);

        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, -2);
        emptyParams.topMargin = Ui.dp(this, 28);
        target.addView(empty, emptyParams);
    }

    private View header(String titleText, String subtitleText, Runnable backAction,
                        String backDescription) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.backButton(this);
        back.setContentDescription(backDescription);
        back.setOnClickListener(v -> backAction.run());
        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        backParams.rightMargin = Ui.dp(this, 8);
        header.addView(back, backParams);

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.bold(this, titleText, 19, Ui.WHITE);
        identity.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        TextView subtitle = Ui.text(this, subtitleText, Ui.CAPTION, Ui.MUTED);
        identity.addView(subtitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        header.addView(identity, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        return header;
    }

    private LinearLayout basePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BLACK);
        page.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 6),
                Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 10));
        return page;
    }

    private void applySelection(TextView action, String planId, String planName) {
        if (selectionInProgress || !availablePlanIds.contains(planId)) return;
        selectionInProgress = true;
        action.setEnabled(false);
        action.setAlpha(.62f);
        try {
            PlanLibraryStore.select(this, planId);
            action.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            Toast.makeText(this, "已设为当前 · " + planName, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception error) {
            selectionInProgress = false;
            action.setEnabled(true);
            action.setAlpha(1f);
            Toast.makeText(this, "计划不可用，请重新同步", Toast.LENGTH_LONG).show();
            reloadProjection();
        }
    }

    private JSONObject findPlan(String planId) {
        JSONArray plans = library.optJSONArray("plans");
        if (plans == null || planId == null || planId.isEmpty()) return null;
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan != null && planId.equals(plan.optString("id"))) return plan;
        }
        return null;
    }

    private static Set<String> planIds(JSONObject library) {
        Set<String> result = new HashSet<>();
        JSONArray plans = library.optJSONArray("plans");
        if (plans == null) return result;
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan == null) continue;
            String id = plan.optString("id");
            if (!id.isEmpty()) result.add(id);
        }
        return result;
    }

    private static ArrayList<Stage> decodeStages(JSONObject plan) {
        JSONArray stages = plan == null ? null : plan.optJSONArray("stages");
        return PlanStore.decode(stages == null ? null : stages.toString());
    }

    private String summary(ArrayList<Stage> stages) {
        long meters = 0;
        long seconds = 0;
        for (Stage stage : stages) {
            if (stage.unit == Stage.Unit.DISTANCE) meters += stage.target;
            else seconds += stage.target;
        }
        String text = stages.size() + " 项内容";
        if (meters > 0) text += String.format(Locale.CHINA, " · %.2f km", meters / 1000d);
        if (seconds > 0) text += " · " + Math.max(1L, (long) Math.ceil(seconds / 60d)) + " 分钟";
        return text;
    }

    @Override public void onBackPressed() {
        if (navigation.screen() == PlanSelectionUiPolicy.Screen.DETAIL) {
            navigation.showList();
            renderPage();
            return;
        }
        if (!openGroupId.isEmpty()) {
            openGroupId = "";
            renderPage();
            return;
        }
        finish();
    }
}
