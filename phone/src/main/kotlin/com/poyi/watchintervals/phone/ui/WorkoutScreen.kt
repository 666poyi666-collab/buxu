package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyi.watchintervals.phone.ui.components.PhoneAction
import com.poyi.watchintervals.phone.ui.components.PhoneButton
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneDivider
import com.poyi.watchintervals.phone.ui.components.PhoneEmptyState
import com.poyi.watchintervals.phone.ui.components.PhonePageHeader
import com.poyi.watchintervals.phone.ui.components.PhoneMetric
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType

/**
 * 训练页。
 *
 * 数据来自手表 [LiveWorkout] 快照,每 5 秒刷新一次。可执行操作完全由快照状态推导,
 * 不提供状态盲按钮,避免在跑动中误发 start 或在空闲时误发 resume。
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
                subtitle = ""
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
                    detail = "打开连接设置完成配对后，这里会显示实时训练数据。",
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
        live == null -> "未在训练"
        live.preparing -> "准备中"
        live.paused -> "已暂停"
        live.planCompleted -> "自由记录中"
        else -> "训练中"
    }
    val stateColor = when {
        live == null -> PhoneColor.NavigationMuted
        live.paused -> PhoneColor.WarningBright
        else -> PhoneColor.ExerciseBright
    }
    val meta = if (live == null) {
        if (error != null) "请检查连接后重试" else "在手表上开始，或点击下方按钮远程开始当前安排"
    } else {
        val parts = ArrayList<String>()
        if (live.stageCount > 0) parts.add("${live.stageName} ${live.stageNumber}/${live.stageCount}")
        parts.joinToString(" · ")
    }
    val description = "$stateText，$duration，$meta"
    PhoneCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        containerColor = PhoneColor.Navigation,
        borderColor = PhoneColor.NavigationLine
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PhoneSpace.lg),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stateText,
                        style = PhoneType.Headline,
                        color = stateColor
                    )
                    Text(
                        text = if (live?.stageName.isNullOrBlank()) "尚未开始训练"
                        else "${live?.stageName} · 第 ${live?.stageNumber}/${live?.stageCount} 项",
                        style = PhoneType.Caption,
                        color = PhoneColor.NavigationMuted,
                        maxLines = 1
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "训练时间",
                        style = PhoneType.Caption,
                        color = PhoneColor.NavigationMuted
                    )
                    Text(
                        text = duration,
                        style = PhoneType.Metric,
                        color = PhoneColor.NavigationText
                    )
                }
            }
            PhoneDivider(color = PhoneColor.NavigationLine)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PhoneMetric(
                    label = "距离",
                    value = live?.let { formatDistance(it.distanceMeters) } ?: "--",
                    valueColor = PhoneColor.ExerciseBright,
                    labelColor = PhoneColor.NavigationMuted,
                    modifier = Modifier.weight(1f)
                )
                PhoneMetric(
                    label = "当前配速",
                    value = live?.let {
                        if (it.currentPaceSecondsPerKm > 0) formatPace(it.currentPaceSecondsPerKm.toLong()) else "--"
                    } ?: "--",
                    valueColor = PhoneColor.StandBright,
                    labelColor = PhoneColor.NavigationMuted,
                    modifier = Modifier.weight(1f)
                )
                PhoneMetric(
                    label = "心率",
                    value = live?.let { if (it.heartRate > 0) "${it.heartRate}" else "--" } ?: "--",
                    valueColor = if ((live?.heartRate ?: 0) > 0) PhoneColor.DangerBright else PhoneColor.NavigationMuted,
                    labelColor = PhoneColor.NavigationMuted,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = if (live == null) meta else
                    "${live.calories} 千卡  ·  ${live.steps} 步  ·  平均心率 " +
                        (if (live.averageHeartRate > 0) "${live.averageHeartRate}" else "--"),
                style = if (live == null) PhoneType.Body else PhoneType.Caption,
                color = PhoneColor.NavigationMuted,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun actionLabel(action: WorkoutAction): String = when (action) {
    WorkoutAction.Start -> "开始训练"
    WorkoutAction.Pause -> "暂停"
    WorkoutAction.Resume -> "继续"
    WorkoutAction.Stop -> "结束"
}

private fun actionDescription(action: WorkoutAction): String = when (action) {
    WorkoutAction.Start -> "开始训练"
    WorkoutAction.Pause -> "暂停训练"
    WorkoutAction.Resume -> "继续训练"
    WorkoutAction.Stop -> "结束训练"
}

private fun actionIcon(action: WorkoutAction): androidx.compose.ui.graphics.vector.ImageVector = when (action) {
    WorkoutAction.Start, WorkoutAction.Resume -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Play
    WorkoutAction.Pause -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Pause
    WorkoutAction.Stop -> com.poyi.watchintervals.phone.ui.icon.PhoneIcons.Stop
}

/** 复用已被单测覆盖的 Java 格式化实现,避免双端文案漂移。 */
private fun formatClock(millis: Long): String = com.poyi.watchintervals.phone.PhoneFormat.duration(millis)

private fun formatDistance(meters: Double): String =
    com.poyi.watchintervals.phone.PhoneFormat.distance(meters)

private fun formatPace(secondsPerKm: Long): String =
    com.poyi.watchintervals.phone.PhoneFormat.paceSeconds(secondsPerKm)
