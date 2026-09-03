package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.poyi.watchintervals.phone.PhonePlanUiModel
import com.poyi.watchintervals.phone.ui.components.PhoneAction
import com.poyi.watchintervals.phone.ui.components.PhoneBadge
import com.poyi.watchintervals.phone.ui.components.PhoneButton
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneCardStyle
import com.poyi.watchintervals.phone.ui.components.PhoneEmptyState
import com.poyi.watchintervals.phone.ui.components.PhoneDivider
import com.poyi.watchintervals.phone.ui.components.PhoneInput
import com.poyi.watchintervals.phone.ui.components.PhoneMetric
import com.poyi.watchintervals.phone.ui.components.PhonePageHeader
import com.poyi.watchintervals.phone.ui.components.stageKindColor
import com.poyi.watchintervals.phone.ui.components.stageKindFill
import com.poyi.watchintervals.phone.ui.icon.PhoneIcons
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType
import java.util.Locale

/**
 * 今日页:当前训练为第一任务,计划库按需进入;库内仍保留列表、详情、编辑器三级。
 *
 * 列表只承担浏览与进入详情;详情提供阶段顺序、编辑、删除和设为手表当前;
 * 编辑器的保存不隐式改变手表当前计划。三级之间的返回顺序由 [PlanRoute] 表达。
 */
@Composable
fun PlanScreen(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
    var libraryVisible by rememberSaveable { mutableStateOf(false) }
    when (val route = state.plan.route) {
        PlanRoute.Library -> if (libraryVisible) {
            PlanLibrary(state, viewModel, onClose = { libraryVisible = false }, modifier)
        } else {
            TodayPlan(
                state = state,
                viewModel = viewModel,
                onManagePlans = { libraryVisible = true },
                modifier = modifier
            )
        }
        is PlanRoute.Detail -> PlanDetail(state, viewModel, route.planId, modifier)
        PlanRoute.Editor -> PlanEditor(state, viewModel, modifier)
    }
}

private val PlanListPadding: PaddingValues
    @Composable
    get() = PaddingValues(
        start = PhoneSpace.xl,
        end = PhoneSpace.xl,
        top = PhoneSpace.sm,
        bottom = PhoneSize.navigation + 38.dp
    )

// -------------------------------------------------------------------- 列表

@Composable
private fun TodayPlan(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    onManagePlans: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plan = state.plan
    val all = plan.groups.flatMap { it.plans } + plan.ungrouped
    val current = all.firstOrNull { it.currentOnWatch }
        ?: all.firstOrNull { it.selectedOnPhone }
    val currentGroupPlans = current?.let { selected ->
        plan.groups.firstOrNull { it.id == selected.groupId }
            ?.plans
            ?.sortedBy { it.sortOrder }
            .orEmpty()
    }.orEmpty()
    val currentIndex = currentGroupPlans.indexOfFirst { it.id == current?.id }
    val visibleSchedule = if (currentGroupPlans.size <= 4) {
        currentGroupPlans
    } else {
        val start = (currentIndex - 1).coerceIn(0, currentGroupPlans.size - 4)
        currentGroupPlans.subList(start, start + 4)
    }
    val live = state.workout.live?.takeIf { it.hasWorkout }
    val latest = state.history.records.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PlanListPadding,
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
    ) {
        item { PhonePageHeader(title = "今天", subtitle = "") }

        if (!state.setup.cloud.configured || !state.setup.cloud.tokenSaved) {
            item {
                PhoneButton(
                    text = "云端未连接 · ChatGPT 计划不会下发",
                    onClick = { viewModel.toggleSetup(true) },
                    action = PhoneAction.Danger,
                    modifier = Modifier.fillMaxWidth(),
                    icon = PhoneIcons.Cloud,
                    contentDescription = "云端未连接，打开设备与同步设置"
                )
            }
        }

        // 核心今日英雄训练卡
        item {
            TodayHeroCard(
                current = current,
                live = live,
                fallbackName = plan.watchCurrentName,
                fallbackGroup = plan.watchCurrentGroup,
                onStartWorkout = { viewModel.selectSection(1) },
                onSelectPlan = { if (current != null) viewModel.selectPlan(current.id) }
            )
        }

        // 快捷功能按键区
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
            ) {
                PhoneButton(
                    text = "管理计划",
                    onClick = onManagePlans,
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f),
                    icon = PhoneIcons.Plan
                )
                PhoneButton(
                    text = "训练历史",
                    onClick = { viewModel.selectSection(2) },
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f),
                    icon = PhoneIcons.History
                )
            }
        }

        // 当前计划所属分组概览
        if (visibleSchedule.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = current?.groupName ?: "当前训练安排",
                            style = PhoneType.Subhead,
                            color = PhoneColor.Text
                        )
                        Text(
                            text = "${(currentIndex + 1).coerceAtLeast(1)} / ${currentGroupPlans.size}",
                            style = PhoneType.Caption,
                            color = PhoneColor.TextDim
                        )
                    }
                    visibleSchedule.forEach { item ->
                        PlanRow(
                            summary = item,
                            onClick = { viewModel.openPlanDetail(item.id) }
                        )
                    }
                }
            }
        }

        // 最近一次训练复盘卡
        item {
            PhoneCard(
                modifier = Modifier.fillMaxWidth(),
                style = PhoneCardStyle.Large
            ) {
                Column(
                    modifier = Modifier.padding(PhoneSpace.lg),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最近一次训练",
                            style = PhoneType.Subhead,
                            color = PhoneColor.Text
                        )
                        Text(
                            text = state.history.summary.ifBlank {
                                if (latest == null) "暂无记录" else "已同步"
                            },
                            style = PhoneType.Caption,
                            color = PhoneColor.TextDim
                        )
                    }
                    PhoneDivider()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PhoneMetric(
                            label = "距离",
                            value = latest?.let {
                                com.poyi.watchintervals.phone.PhoneFormat.distance(it.distanceMeters)
                            } ?: "--",
                            valueColor = PhoneColor.Exercise,
                            modifier = Modifier.weight(1f)
                        )
                        PhoneMetric(
                            label = "时长",
                            value = latest?.let {
                                com.poyi.watchintervals.phone.PhoneFormat.duration(it.durationMs)
                            } ?: "--",
                            modifier = Modifier.weight(1f)
                        )
                        PhoneMetric(
                            label = "平均心率",
                            value = latest?.averageHeartRate?.takeIf { it > 0 }?.let { "$it bpm" } ?: "--",
                            valueColor = PhoneColor.Danger,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (state.sync.message.isNotBlank() || state.sync.lastSyncLabel.isNotBlank()) {
            item {
                Text(
                    text = state.sync.message.ifBlank { state.sync.lastSyncLabel },
                    style = PhoneType.Caption,
                    color = toneColor(state.sync.tone),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun TodayHeroCard(
    current: PlanSummary?,
    live: LiveWorkout?,
    fallbackName: String,
    fallbackGroup: String,
    onStartWorkout: () -> Unit,
    onSelectPlan: () -> Unit
) {
    val containerBg = Color(0xFF0F172A)
    val borderCol = Color(0xFF1E293B)

    PhoneCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = containerBg,
        borderColor = borderCol,
        style = PhoneCardStyle.Large
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PhoneSpace.lg),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
        ) {
            // 顶栏状态徽章与分组标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (live != null) PhoneColor.ExerciseBright else PhoneColor.StandBright)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (live != null) "训练进行中" else "今日当前安排",
                        style = PhoneType.Caption,
                        color = if (live != null) PhoneColor.ExerciseBright else PhoneColor.StandBright
                    )
                }

                val groupText = current?.groupName?.ifBlank { null } ?: fallbackGroup.ifBlank { "间歇训练" }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = groupText,
                        style = PhoneType.Caption,
                        color = PhoneColor.NavigationMuted
                    )
                }
            }

            // 计划主标题
            val titleText = live?.stageName?.ifBlank { null }
                ?: current?.name
                ?: fallbackName.ifBlank { "尚未选择训练" }
            Text(
                text = titleText,
                style = PhoneType.Display,
                color = PhoneColor.NavigationText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )

            // 阶段动线多色条
            if (current != null && current.stages.isNotEmpty()) {
                PlanStageTrack(current.stages)
                Text(
                    text = current.sequence,
                    style = PhoneType.BodyStrong,
                    color = PhoneColor.StandBright
                )
            }

            // 核心训练要求/教练指导卡片
            val requirementText = current?.requirement?.trim().orEmpty()
            if (requirementText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.7f))
                        .padding(PhoneSpace.md)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "💡 训练指导与自适应建议",
                            style = PhoneType.Caption,
                            color = PhoneColor.WarningBright
                        )
                        Text(
                            text = requirementText,
                            style = PhoneType.Body,
                            color = PhoneColor.NavigationText.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // 底部主操作按钮
            PhoneButton(
                text = if (live == null) "打开训练控制" else "查看实时训练",
                onClick = onStartWorkout,
                action = PhoneAction.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = PhoneIcons.Play
            )
        }
    }
}

@Composable
private fun PlanStageTrack(stages: List<StageDraft>) {
    if (stages.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        stages.forEach { stage ->
            Box(
                modifier = Modifier
                    .weight(stage.target.coerceAtLeast(1).toFloat())
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(stageKindColor(stage.kind.wire))
            )
        }
    }
}

@Composable
private fun PlanLibrary(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val plan = state.plan
    var pendingGroupCreate by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PlanListPadding,
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                        contentDescription = "返回今天"
                    }
                ) {
                    Icon(PhoneIcons.Back, contentDescription = null, tint = PhoneColor.Text)
                }
                Text(
                    text = "计划库",
                    style = PhoneType.Title,
                    color = PhoneColor.Text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item { WatchCurrentStrip(state) }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
            ) {
                PhoneButton(
                    text = "新建安排",
                    onClick = { viewModel.createPlan() },
                    action = PhoneAction.Primary,
                    modifier = Modifier.weight(1f),
                    icon = PhoneIcons.Add
                )
                PhoneButton(
                    text = "新建分组",
                    onClick = { pendingGroupCreate = true },
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f),
                    icon = PhoneIcons.Plan
                )
            }
        }
        if (plan.savedCount == 0) {
            item {
                PhoneEmptyState(
                    title = "还没有训练安排",
                    detail = "先建立第 1 天，之后可以继续按训练周期分组。",
                    actionText = "新建第 1 个安排",
                    onAction = { viewModel.createPlan() }
                )
            }
        }
        items(plan.groups, key = { "group-${it.id}" }) { group ->
            PlanGroupCard(group = group, state = state, viewModel = viewModel)
        }
        if (plan.ungrouped.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)
                ) {
                    Text(text = "未分组", style = PhoneType.Subhead, color = PhoneColor.Text)
                    plan.ungrouped.forEachIndexed { index, item ->
                        PlanRow(summary = item, onClick = { viewModel.openPlanDetail(item.id) })
                        if (index < plan.ungrouped.lastIndex) {
                            PhoneDivider(modifier = Modifier.padding(start = 24.dp))
                        }
                    }
                }
            }
        }
    }
    if (pendingGroupCreate) {
        InputDialog(
            title = "新建分组",
            label = "例如：30日减脂",
            initial = "",
            confirmText = "创建",
            onConfirm = { name ->
                pendingGroupCreate = false
                if (name.isNotBlank()) viewModel.createGroup(name.trim())
            },
            onDismiss = { pendingGroupCreate = false }
        )
    }
}

@Composable
private fun WatchCurrentStrip(state: PhoneUiState) {
    val plan = state.plan
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(PhoneRadius.control))
            .background(PhoneColor.Navigation)
            .border(1.dp, PhoneColor.NavigationLine, RoundedCornerShape(PhoneRadius.control))
            .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PhoneColor.Move)
        )
        Spacer(modifier = Modifier.width(PhoneSpace.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "手表当前安排", style = PhoneType.Caption, color = PhoneColor.NavigationMuted)
            Text(
                text = plan.watchCurrentName.ifBlank { "连接后读取" },
                style = PhoneType.Subhead,
                color = PhoneColor.NavigationText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (plan.watchCurrentGroup.isBlank()) "等待连接" else plan.watchCurrentGroup,
            style = PhoneType.Caption,
            color = PhoneColor.NavigationMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlanGroupCard(
    group: PlanGroupBlock,
    state: PhoneUiState,
    viewModel: PhoneViewModel
) {
    var pendingDelete by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PhoneSize.touchTarget),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.name,
                style = PhoneType.Subhead,
                color = PhoneColor.Text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = "${group.plans.size} 个安排", style = PhoneType.Caption, color = PhoneColor.TextDim)
            IconButton(
                onClick = { viewModel.createPlanInGroup(group.id, group.name) },
                modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                    contentDescription = "在${group.name}中新建训练安排"
                }
            ) {
                Icon(PhoneIcons.Add, contentDescription = null, tint = PhoneColor.Move)
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                        contentDescription = "管理分组${group.name}"
                    }
                ) {
                    Icon(PhoneIcons.More, contentDescription = null, tint = PhoneColor.TextDim)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = {
                            Icon(PhoneIcons.Edit, contentDescription = null, tint = PhoneColor.TextDim)
                        },
                        onClick = {
                            menuExpanded = false
                            pendingRename = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (group.plans.isEmpty()) "删除空分组"
                                else "删除分组及 ${group.plans.size} 个安排",
                                color = PhoneColor.Danger
                            )
                        },
                        leadingIcon = {
                            Icon(PhoneIcons.Delete, contentDescription = null, tint = PhoneColor.Danger)
                        },
                        onClick = {
                            menuExpanded = false
                            pendingDelete = true
                        },
                    )
                }
            }
        }
        group.plans.forEachIndexed { index, item ->
            PlanRow(summary = item, onClick = { viewModel.openPlanDetail(item.id) })
            if (index < group.plans.lastIndex) {
                PhoneDivider(modifier = Modifier.padding(start = 24.dp))
            }
        }
    }
    if (pendingDelete) {
        ConfirmDialog(
            title = if (group.plans.isEmpty()) "删除“${group.name}”？"
            else "删除“${group.name}”及其中 ${group.plans.size} 个安排？",
            message = if (group.plans.isEmpty()) {
                "仅删除这个空分组。"
            } else {
                "这会同时删除该分组和其中的 ${group.plans.size} 个安排，且无法恢复；其他分组和安排不受影响。"
            },
            confirmText = if (group.plans.isEmpty()) "删除" else "全部删除",
            onConfirm = {
                pendingDelete = false
                viewModel.deleteGroup(group.id)
            },
            onDismiss = { pendingDelete = false }
        )
    }
    if (pendingRename) {
        InputDialog(
            title = "修改分组名称",
            label = "例如：30日减脂",
            initial = group.name,
            confirmText = "保存",
            onConfirm = { name ->
                pendingRename = false
                if (name.isNotBlank()) viewModel.renameGroup(group.id, name.trim())
            },
            onDismiss = { pendingRename = false }
        )
    }
}

@Composable
internal fun InputDialog(
    title: String,
    label: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        PhoneCard(
            modifier = Modifier.fillMaxWidth(),
            style = com.poyi.watchintervals.phone.ui.components.PhoneCardStyle.Large
        ) {
            Column(
                modifier = Modifier.padding(PhoneSpace.xl),
                verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
            ) {
                Text(text = title, style = PhoneType.Subhead, color = PhoneColor.Text)
                PhoneInput(value = text, onValueChange = { text = it }, label = label)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    PhoneButton(
                        text = "取消",
                        onClick = onDismiss,
                        action = PhoneAction.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    PhoneButton(
                        text = confirmText,
                        onClick = { onConfirm(text.trim()) },
                        action = PhoneAction.Primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanRow(summary: PlanSummary, onClick: () -> Unit) {
    val container = if (summary.selectedOnPhone) PhoneColor.Surface else PhoneColor.SurfaceHigh
    val stroke = if (summary.selectedOnPhone) PhoneColor.Move else PhoneColor.Border
    val description = PhoneUiContract.planRowDescription(summary.name, summary.summary)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhoneSize.touchTarget)
            .clip(RoundedCornerShape(PhoneRadius.control))
            .clickableWithRole(onClick = onClick, contentDescription = description),
        shape = RoundedCornerShape(PhoneRadius.control),
        color = container,
        border = androidx.compose.foundation.BorderStroke(1.dp, stroke)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = PhoneSpace.md,
                vertical = PhoneSpace.sm
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.name,
                    style = PhoneType.BodyStrong,
                    color = PhoneColor.Text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (summary.currentOnWatch || summary.selectedOnPhone) {
                    PhoneBadge(
                        text = if (summary.currentOnWatch) "手表当前" else "手机已选",
                        container = if (summary.selectedOnPhone) PhoneColor.FillSelected else PhoneColor.Surface,
                        content = if (summary.currentOnWatch) PhoneColor.Success else PhoneColor.Move
                    )
                }
            }
            Text(
                text = summary.summary,
                style = PhoneType.Caption,
                color = PhoneColor.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary.sequence,
                style = PhoneType.Caption,
                color = PhoneColor.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// -------------------------------------------------------------------- 详情

@Composable
private fun PlanDetail(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    planId: String,
    modifier: Modifier = Modifier
) {
    val summary = remember(state.plan, planId) {
        val all = state.plan.groups.flatMap { it.plans } + state.plan.ungrouped
        all.firstOrNull { it.id == planId }
    }
    if (summary == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PlanListPadding
        ) {
            item {
                PhoneEmptyState(
                    title = "安排已不存在",
                    detail = "该安排可能已在其他设备上被删除。",
                    actionText = "返回计划列表",
                    onAction = { viewModel.openPlanLibrary() }
                )
            }
        }
        return
    }
    var pendingDelete by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PlanListPadding,
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhoneButton(
                    text = "返回计划",
                    onClick = { viewModel.openPlanLibrary() },
                    action = PhoneAction.Ghost,
                    contentDescription = "返回训练计划列表",
                    icon = PhoneIcons.Back
                )
                Spacer(modifier = Modifier.weight(1f))
                PhoneButton(
                    text = "编辑",
                    onClick = { viewModel.editPlan(planId) },
                    action = PhoneAction.Secondary,
                    icon = PhoneIcons.Edit
                )
            }
        }
        item {
            Text(text = summary.name, style = PhoneType.Display, color = PhoneColor.Text)
            Text(
                text = "${summary.groupName} · ${summary.summary}",
                style = PhoneType.Body,
                color = PhoneColor.TextDim
            )
        }
        item {
            PhoneCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(PhoneSpace.lg)) {
                    Text(text = "训练节奏", style = PhoneType.Label, color = PhoneColor.TextDim)
                    Text(
                        text = summary.sequence,
                        style = PhoneType.Title,
                        color = PhoneColor.Text
                    )
                    Spacer(modifier = Modifier.height(PhoneSpace.xs))
                    Text(
                        text = summary.requirement.ifBlank { "按阶段顺序完成训练。" },
                        style = PhoneType.Caption,
                        color = PhoneColor.TextDim
                    )
                }
            }
        }
        item {
            Text(text = "阶段明细", style = PhoneType.Subhead, color = PhoneColor.Text)
        }
        itemsIndexed(summary.stages) { index, stage ->
            StageReadRow(index = index, stage = stage)
        }
        item {
            Spacer(modifier = Modifier.height(PhoneSpace.md))
            PhoneButton(
                text = "设为手表当前安排",
                onClick = { viewModel.selectPlan(planId) },
                action = PhoneAction.Primary,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "设为当前安排并同步到手表",
                icon = PhoneIcons.Check
            )
        }
        item {
            PhoneButton(
                text = "删除这个安排",
                onClick = { pendingDelete = true },
                action = PhoneAction.Danger,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "删除这个安排",
                icon = PhoneIcons.Delete
            )
        }
    }
    if (pendingDelete) {
        ConfirmDialog(
            title = "删除“${summary.name}”？",
            message = "只删除这一项；其他 ${kotlin.math.max(0, state.plan.savedCount - 1)} 个安排和所有分组保持不变。同步成功后，手机、云端和手表会移除同一个安排 ID。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = false
                viewModel.deletePlan(planId)
            },
            onDismiss = { pendingDelete = false }
        )
    }
}

@Composable
private fun StageReadRow(index: Int, stage: StageDraft) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhoneSize.controlCompact)
            .clip(RoundedCornerShape(PhoneRadius.chip))
            .background(if (index % 2 == 0) PhoneColor.SurfaceHigh else PhoneColor.Surface)
            .padding(horizontal = PhoneSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = String.format(Locale.CHINA, "%02d", index + 1),
            style = PhoneType.Caption,
            color = PhoneColor.TextDim,
            modifier = Modifier.width(38.dp)
        )
        Text(
            text = stageLabel(stage),
            style = PhoneType.BodyStrong,
            color = PhoneColor.Text,
            modifier = Modifier.weight(1f)
        )
    }
}

// -------------------------------------------------------------------- 编辑器

@Composable
private fun PlanEditor(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
    val draft = state.plan.draft
    var pendingLeave by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PlanListPadding,
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PhoneButton(
                    text = "返回详情",
                    onClick = {
                        if (draft.dirty) pendingLeave = true else viewModel.leaveEditor()
                    },
                    action = PhoneAction.Ghost,
                    contentDescription = "返回安排详情"
                )
                Spacer(modifier = Modifier.weight(1f))
                PhoneButton(
                    text = "保存",
                    onClick = { viewModel.savePlan() },
                    action = PhoneAction.Primary,
                    icon = PhoneIcons.Check
                )
            }
        }
        item {
            Text(
                text = if (draft.id.isBlank()) "新建安排" else "编辑安排",
                style = PhoneType.Display,
                color = PhoneColor.Text
            )
        }
        item {
            PhoneCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PhoneSpace.lg),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    Text(text = "安排信息", style = PhoneType.Subhead, color = PhoneColor.Text)
                    PhoneInput(
                        value = draft.name,
                        onValueChange = { viewModel.updateDraft(name = it) },
                        label = "安排名称，例如：第1天"
                    )
                    PlanGroupPicker(
                        groups = state.plan.groups,
                        selectedGroupId = draft.groupId,
                        selectedGroupName = draft.group,
                        onSelect = { id, name -> viewModel.selectDraftGroup(id, name) }
                    )
                    PhoneInput(
                        value = draft.requirement,
                        onValueChange = { viewModel.updateDraft(requirement = it) },
                        label = "今天的训练说明（可选）",
                        singleLine = false,
                        minLines = 3
                    )
                }
            }
        }
        item {
            PhoneCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(PhoneSpace.lg),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    Text(text = "快速填充训练内容", style = PhoneType.Subhead, color = PhoneColor.Text)
                    Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                        PhoneButton(
                            text = "1千米 + 200米",
                            onClick = { viewModel.applyTemplate(false) },
                            action = PhoneAction.Secondary,
                            modifier = Modifier.weight(1f)
                        )
                        PhoneButton(
                            text = "法特莱克跑",
                            onClick = { viewModel.applyTemplate(true) },
                            action = PhoneAction.Secondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        item {
            Text(text = "训练内容", style = PhoneType.Title, color = PhoneColor.Text)
            Text(
                text = "每项都可选择按时间或距离，支持交替组合",
                style = PhoneType.Caption,
                color = PhoneColor.TextDim
            )
        }
        if (draft.stages.isEmpty()) {
            item {
                PhoneEmptyState(
                    title = "还没有训练阶段",
                    detail = "从下方添加跑步、快走或休息"
                )
            }
        }
        itemsIndexed(draft.stages, key = { index, _ -> index }) { index, stage ->
            StageEditorCard(
                index = index,
                stage = stage,
                count = draft.stages.size,
                onKindSelected = { viewModel.selectStageKind(index, it) },
                onUnitSelected = { viewModel.selectStageUnit(index, it) },
                onTargetChange = { viewModel.updateStageTarget(index, it) },
                onMove = { delta -> viewModel.moveStage(index, delta) },
                onRemove = { viewModel.removeStage(index) }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                PhoneButton(
                    text = "+ 跑步",
                    onClick = { viewModel.addStage(StageKind.RUN, StageUnit.DISTANCE, 1000) },
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f)
                )
                PhoneButton(
                    text = "+ 快走",
                    onClick = { viewModel.addStage(StageKind.WALK, StageUnit.DISTANCE, 200) },
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f)
                )
                PhoneButton(
                    text = "+ 休息",
                    onClick = { viewModel.addStage(StageKind.REST, StageUnit.TIME, 60) },
                    action = PhoneAction.Secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            PhoneButton(
                text = "保存安排",
                onClick = { viewModel.savePlan() },
                action = PhoneAction.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = PhoneIcons.Check
            )
            Text(
                text = "保存后自动同步；只有“设为当前”才会改变手表正在使用的安排。",
                style = PhoneType.Caption,
                color = PhoneColor.TextDim
            )
        }
    }
    if (pendingLeave) {
        ConfirmDialog(
            title = "放弃未保存的修改？",
            message = "返回后，本次对名称和阶段的修改不会保留。",
            confirmText = "放弃",
            onConfirm = {
                pendingLeave = false
                viewModel.leaveEditor()
            },
            onDismiss = { pendingLeave = false }
        )
    }
}

@Composable
private fun PlanGroupPicker(
    groups: List<PlanGroupBlock>,
    selectedGroupId: String,
    selectedGroupName: String,
    onSelect: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
        Text(text = "所属分组", style = PhoneType.Caption, color = PhoneColor.TextDim)
        Box(modifier = Modifier.fillMaxWidth()) {
            PhoneButton(
                text = selectedGroupName.ifBlank { "选择分组" },
                onClick = { expanded = true },
                action = PhoneAction.Secondary,
                modifier = Modifier.fillMaxWidth(),
                contentDescription = "选择所属分组，当前${selectedGroupName.ifBlank { "未选择" }}"
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                groups.forEach { group ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                group.name,
                                color = if (group.id == selectedGroupId) {
                                    PhoneColor.Move
                                } else {
                                    PhoneColor.Text
                                }
                            )
                        },
                        trailingIcon = if (group.id == selectedGroupId) {
                            {
                                Icon(
                                    PhoneIcons.Check,
                                    contentDescription = null,
                                    tint = PhoneColor.Move
                                )
                            }
                        } else null,
                        onClick = {
                            expanded = false
                            onSelect(group.id, group.name)
                        }
                    )
                }
            }
        }
        if (groups.isEmpty()) {
            Text(
                text = "请先返回计划库创建分组",
                style = PhoneType.Caption,
                color = PhoneColor.Warning
            )
        }
    }
}

@Composable
private fun StageEditorCard(
    index: Int,
    stage: StageDraft,
    count: Int,
    onKindSelected: (StageKind) -> Unit,
    onUnitSelected: (StageUnit) -> Unit,
    onTargetChange: (Int) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    PhoneCard(
        modifier = Modifier.fillMaxWidth(),
        style = com.poyi.watchintervals.phone.ui.components.PhoneCardStyle.List
    ) {
        Column(
            modifier = Modifier.padding(PhoneSpace.md),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(PhoneRadius.chip))
                        .background(PhoneColor.SurfaceDeep),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.CHINA, "%02d", index + 1),
                        style = PhoneType.Caption,
                        color = PhoneColor.TextDim
                    )
                }
                Spacer(modifier = Modifier.width(PhoneSpace.sm))
                Text(
                    text = "第 ${index + 1} 阶段",
                    style = PhoneType.BodyStrong,
                    color = PhoneColor.Text,
                    modifier = Modifier.weight(1f)
                )
                if (count > 1) {
                    IconButton(
                        onClick = { onMove(-1) },
                        enabled = index > 0,
                        modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                            contentDescription = PhoneUiContract.stageMoveUpDescription(index)
                        }
                    ) {
                        Icon(PhoneIcons.MoveUp, contentDescription = null)
                    }
                    IconButton(
                        onClick = { onMove(1) },
                        enabled = index < count - 1,
                        modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                            contentDescription = PhoneUiContract.stageMoveDownDescription(index)
                        }
                    ) {
                        Icon(PhoneIcons.MoveDown, contentDescription = null)
                    }
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(PhoneSize.touchTarget).semantics {
                        contentDescription = PhoneUiContract.stageRemoveDescription(index)
                    }
                ) {
                    Icon(PhoneIcons.Delete, contentDescription = null, tint = PhoneColor.Danger)
                }
            }
            Text(text = "阶段类型", style = PhoneType.Caption, color = PhoneColor.TextDim)
            SegmentedChoice(
                options = StageKind.entries,
                selected = stage.kind,
                label = { PhonePlanUiModel.kindName(it.wire) },
                onSelect = onKindSelected,
                contentDescription = { kind ->
                    "第${index + 1}阶段类型，${PhonePlanUiModel.kindName(kind.wire)}"
                }
            )
            Text(text = "目标单位", style = PhoneType.Caption, color = PhoneColor.TextDim)
            SegmentedChoice(
                options = if (stage.kind == StageKind.REST) listOf(StageUnit.TIME) else StageUnit.entries,
                selected = stage.unit,
                label = { PhoneUiContract.unitName(it) },
                onSelect = onUnitSelected,
                contentDescription = { unit ->
                    "第${index + 1}阶段目标单位，${PhoneUiContract.unitName(unit)}"
                }
            )
            var text by remember(index, stage.target) { mutableStateOf(stage.target.toString()) }
            var invalid by remember { mutableStateOf(false) }
            PhoneInput(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    val parsed = raw.toIntOrNull()
                    if (parsed == null || parsed < 1) {
                        invalid = true
                    } else {
                        invalid = false
                        onTargetChange(parsed)
                    }
                },
                label = if (stage.unit == StageUnit.DISTANCE) "目标距离（米）" else "目标时间（秒）",
                isError = invalid,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Number
                )
            )
        }
    }
}

@Composable
private fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    contentDescription: (T) -> String
) {
    val shape = RoundedCornerShape(PhoneRadius.control)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .clip(shape)
            .background(PhoneColor.SurfaceHigh)
            .border(1.dp, PhoneColor.Border, shape)
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhoneSize.touchTarget)
                    .background(if (active) PhoneColor.FillSelected else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .semantics { this.contentDescription = contentDescription(option) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = PhoneType.Label,
                    color = if (active) PhoneColor.Move else PhoneColor.TextDim
                )
            }
        }
    }
}

// -------------------------------------------------------------------- 通用

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    confirmAction: PhoneAction = PhoneAction.Danger,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        PhoneCard(modifier = Modifier.fillMaxWidth(), style = com.poyi.watchintervals.phone.ui.components.PhoneCardStyle.Large) {
            Column(
                modifier = Modifier.padding(PhoneSpace.xl),
                verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
            ) {
                Text(text = title, style = PhoneType.Subhead, color = PhoneColor.Text)
                Text(text = message, style = PhoneType.Body, color = PhoneColor.TextDim)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    PhoneButton(
                        text = "取消",
                        onClick = onDismiss,
                        action = PhoneAction.Secondary,
                        modifier = Modifier.weight(1f)
                    )
                    PhoneButton(
                        text = confirmText,
                        onClick = onConfirm,
                        action = confirmAction,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

internal fun Modifier.clickableWithRole(
    onClick: () -> Unit,
    contentDescription: String
): Modifier = this
    .semantics { this.contentDescription = contentDescription }
    .clickable { onClick() }

internal fun stageLabel(stage: StageDraft): String =
    PhonePlanUiModel.kindName(stage.kind.wire) + " " + targetLabel(stage)

private fun targetLabel(stage: StageDraft): String {
    val target = stage.target.coerceAtLeast(1)
    return if (stage.unit == StageUnit.DISTANCE) {
        if (target >= 1000 && target % 1000 == 0) "${target / 1000} 公里"
        else "$target 米"
    } else {
        if (target >= 60 && target % 60 == 0) "${target / 60} 分钟"
        else "$target 秒"
    }
}

@Composable
internal fun StageKindIcon(kind: StageKind, modifier: Modifier = Modifier) {
    Icon(
        imageVector = PhoneIcons.Workout,
        contentDescription = null,
        tint = stageKindColor(kind.wire),
        modifier = modifier.size(20.dp)
    )
}

internal fun stageFill(kind: StageKind): Color = stageKindFill(kind.wire)
