package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyi.watchintervals.phone.ui.components.PhoneAction
import com.poyi.watchintervals.phone.ui.components.PhoneButton
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneCardStyle
import com.poyi.watchintervals.phone.ui.components.PhoneDivider
import com.poyi.watchintervals.phone.ui.components.PhoneEmptyState
import com.poyi.watchintervals.phone.ui.components.PhonePageHeader
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType

/**
 * 训练页：以现代奢华运动穿戴 HUD 视觉呈现，实时同步手表端高频运动数据。
 */
@Composable
fun WorkoutScreen(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
    val workout = state.workout
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PhoneSpace.xl,
            end = PhoneSpace.xl,
            top = PhoneSpace.sm,
            bottom = PhoneSize.navigation + 38.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
    ) {
        item {
            PhonePageHeader(
                title = "训练",
                subtitle = if (workout.live != null) "手表实时运动数据监控" else "随时开启间歇训练"
            )
        }
        item {
            LiveDial(
                live = workout.live,
                error = workout.error
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
            ) {
                workout.actions.forEach { action ->
                    val isStop = action == WorkoutAction.Stop
                    PhoneButton(
                        text = actionLabel(action),
                        onClick = { viewModel.control(action) },
                        action = if (isStop) PhoneAction.Danger else PhoneAction.Primary,
                        modifier = Modifier.weight(1f),
                        contentDescription = actionDescription(action),
                        icon = actionIcon(action)
                    )
                }
            }
        }
        if (!workout.transportReady) {
            item {
                PhoneEmptyState(
                    title = "尚未连接手表",
                    detail = "打开右上角连接设置完成配对后，这里会实时显示手表的训练数据与控制指令。",
                    actionText = "打开连接设置",
                    onAction = { viewModel.toggleSetup(true) }
                )
            }
        }
        if (workout.notice != null) {
            item {
                Text(
                    text = workout.notice,
                    style = PhoneType.Caption,
                    color = PhoneColor.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LiveDial(
    live: LiveWorkout?,
    error: String?
) {
    val duration = if (live == null) "--:--" else formatClock(live.activeDurationMs)
    val stateText = when {
        live == null && error != null -> "无法读取手表状态"
        live == null -> "准备就绪"
        live.preparing -> "准备起跑"
        live.paused -> "训练已暂停"
        live.planCompleted -> "自由记录中"
        else -> "训练进行中"
    }
    val stateColor = when {
        live == null -> PhoneColor.NavigationMuted
        live.paused -> PhoneColor.WarningBright
        else -> PhoneColor.ExerciseBright
    }

    val containerBg = Color(0xFF0F172A) // 优雅深邃暗夜黑蓝
    val borderCol = Color(0xFF1E293B)

    PhoneCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$stateText，用时 $duration" },
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
            // 顶部状态徽章与阶段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(PhoneSpace.xs))
                    Text(
                        text = stateText,
                        style = PhoneType.Headline,
                        color = stateColor
                    )
                }
                if (live != null && live.stageCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${live.stageName} · ${live.stageNumber}/${live.stageCount}",
                            style = PhoneType.Caption,
                            color = PhoneColor.NavigationText,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 中央巨大时长展示
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PhoneSpace.xs),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "训练时间",
                    style = PhoneType.Caption,
                    color = PhoneColor.NavigationMuted,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = duration,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = PhoneColor.NavigationText,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
            }

            PhoneDivider(color = borderCol)

            // 核心 4 指标全景网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HudMetricItem(
                    label = "累计距离",
                    value = live?.let { formatDistance(it.distanceMeters) } ?: "--",
                    unit = "km",
                    color = PhoneColor.ExerciseBright,
                    modifier = Modifier.weight(1f)
                )
                HudMetricItem(
                    label = "当前配速",
                    value = live?.let {
                        if (it.currentPaceSecondsPerKm > 0) formatPace(it.currentPaceSecondsPerKm.toLong()) else "--"
                    } ?: "--",
                    unit = "/km",
                    color = PhoneColor.StandBright,
                    modifier = Modifier.weight(1f)
                )
                HudMetricItem(
                    label = "实时心率",
                    value = live?.let { if (it.heartRate > 0) "${it.heartRate}" else "--" } ?: "--",
                    unit = "bpm",
                    color = if ((live?.heartRate ?: 0) > 0) PhoneColor.DangerBright else PhoneColor.NavigationMuted,
                    modifier = Modifier.weight(1f)
                )
                HudMetricItem(
                    label = "消耗热量",
                    value = live?.let { "${it.calories}" } ?: "--",
                    unit = "kcal",
                    color = PhoneColor.WarningBright,
                    modifier = Modifier.weight(1f)
                )
            }

            // 底部辅助生理数据带
            if (live != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "步频 ${live.cadenceSpm} spm",
                            style = PhoneType.Caption,
                            color = PhoneColor.NavigationMuted
                        )
                        Text(
                            text = "步数 ${live.steps}",
                            style = PhoneType.Caption,
                            color = PhoneColor.NavigationMuted
                        )
                        Text(
                            text = "平均心率 " + (if (live.averageHeartRate > 0) "${live.averageHeartRate} bpm" else "--"),
                            style = PhoneType.Caption,
                            color = PhoneColor.NavigationMuted
                        )
                    }
                }
            } else {
                Text(
                    text = if (error != null) "请检查连接后重试" else "在手表上抬腕开始，或点击下方按钮远程控制安排",
                    style = PhoneType.Body,
                    color = PhoneColor.NavigationMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HudMetricItem(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = PhoneType.Caption,
            color = PhoneColor.NavigationMuted,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = unit,
            fontSize = 11.sp,
            color = PhoneColor.NavigationMuted
        )
    }
}

private fun actionLabel(action: WorkoutAction): String = when (action) {
    WorkoutAction.Start -> "开始训练"
    WorkoutAction.Pause -> "暂停训练"
    WorkoutAction.Resume -> "继续训练"
    WorkoutAction.Stop -> "结束训练"
}

private fun actionDescription(action: WorkoutAction): String = when (action) {
    WorkoutAction.Start -> "开始当前训练安排"
    WorkoutAction.Pause -> "暂停当前训练"
    WorkoutAction.Resume -> "继续当前训练"
    WorkoutAction.Stop -> "结束并保存训练"
}

private fun actionIcon(action: WorkoutAction): androidx.compose.ui.graphics.vector.ImageVector = when (action) {
    WorkoutAction.Start, WorkoutAction.Resume -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Play
    WorkoutAction.Pause -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Pause
    WorkoutAction.Stop -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Stop
}

private fun formatClock(millis: Long): String = com.poyi.watchintervals.phone.PhoneFormat.duration(millis)

private fun formatDistance(meters: Double): String =
    com.poyi.watchintervals.phone.PhoneFormat.distance(meters)

private fun formatPace(secondsPerKm: Long): String =
    com.poyi.watchintervals.phone.PhoneFormat.paceSeconds(secondsPerKm)
