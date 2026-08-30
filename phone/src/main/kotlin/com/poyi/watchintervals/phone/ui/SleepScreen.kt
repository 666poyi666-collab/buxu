package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poyi.watchintervals.phone.PhoneFormat
import com.poyi.watchintervals.phone.PhoneSleepOverview
import com.poyi.watchintervals.phone.PhoneSleepTimeline
import com.poyi.watchintervals.phone.ui.chart.SleepStageBar
import com.poyi.watchintervals.phone.ui.chart.SleepStageTimeline
import com.poyi.watchintervals.phone.ui.chart.SleepWeekTrend
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneCardStyle
import com.poyi.watchintervals.phone.ui.components.PhoneEmptyState
import com.poyi.watchintervals.phone.ui.components.PhonePageHeader
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 睡眠页。
 *
 * 先给近 7 晚总时长趋势,再按晚展示评分与时长双指标、真实阶段时间线和明确生理字段。
 * 系统未提供的指标统一显示 "--",不得用 0 或估算值补齐;刷新失败时保留缓存内容。
 */
@Composable
fun SleepScreen(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
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
                title = "睡眠",
                subtitle = ""
            )
        }
        when (val sleep = state.sleep) {
            SleepState.Idle -> item {
                Text(
                    text = "已同步的数据会保存在本机，离线也能查看",
                    style = PhoneType.Body,
                    color = PhoneColor.TextDim
                )
            }

            SleepState.Loading -> item {
                Text(
                    text = "正在从手表读取最近 31 天…",
                    style = PhoneType.Body,
                    color = PhoneColor.TextDim
                )
            }

            is SleepState.Empty -> item {
                PhoneEmptyState(title = sleep.title, detail = sleep.detail)
            }

            is SleepState.Ready -> {
                item {
                    Text(
                        text = sleep.summary,
                        style = PhoneType.Body,
                        color = PhoneColor.TextDim
                    )
                }
                if (sleep.week.nights.isNotEmpty()) {
                    item {
                        PhoneCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(PhoneSpace.lg)) {
                                Text(text = "近 7 晚", style = PhoneType.Subhead, color = PhoneColor.Text)
                                Text(
                                    text = "柱顶为小时数；数据来自本机最近一次成功同步",
                                    style = PhoneType.Caption,
                                    color = PhoneColor.TextDim
                                )
                                SleepWeekTrend(week = sleep.week)
                            }
                        }
                    }
                }
                items(sleep.nights, key = { "${it.overview.timestamp}-${it.timeline.startTime}" }) { night ->
                    SleepNightCard(night = night)
                }
            }
        }
    }
}

@Composable
private fun SleepNightCard(night: SleepNightUi) {
    val overview = night.overview
    val timeline = night.timeline
    val displayTime = if (timeline.available()) timeline.endTime else overview.timestamp
    val date = if (displayTime > 0L) {
        SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date(displayTime))
    } else {
        "日期未返回"
    }
    val fullDate = if (timeline.available()) {
        "$date  ·  " +
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeline.startTime)) +
            " – " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeline.endTime))
    } else {
        date
    }

    PhoneCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PhoneSpace.lg),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
        ) {
            Text(text = fullDate, style = PhoneType.BodyStrong, color = PhoneColor.TextDim)

            Row(horizontalArrangement = Arrangement.spacedBy(PhoneSpace.sm)) {
                SleepHero(
                    label = "睡眠评分",
                    value = if (overview.scoreAvailable) "${overview.sleepScore} 分" else "--",
                    modifier = Modifier.weight(1f)
                )
                SleepHero(
                    label = "总时长",
                    value = if (overview.durationAvailable) {
                        PhoneFormat.minutesHuman(overview.totalDurationMinutes.toInt())
                    } else "--",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(text = "阶段时间线", style = PhoneType.Subhead, color = PhoneColor.Text)
            if (timeline.available()) {
                SleepStageTimeline(timeline = timeline, modifier = Modifier.fillMaxWidth())
                if (timeline.unknownCount() > 0) {
                    Text(
                        text = "${timeline.unknownCount()} 段厂商未知阶段以灰色保留，没有猜测成深睡或 REM。",
                        style = PhoneType.Caption,
                        color = PhoneColor.TextDim
                    )
                }
            } else {
                Text(
                    text = "系统没有返回有效的阶段起止时间，本晚只显示时长构成。",
                    style = PhoneType.Body,
                    color = PhoneColor.TextDim
                )
            }

            Text(text = "阶段构成", style = PhoneType.Subhead, color = PhoneColor.Text)
            if (overview.stageBreakdownAvailable) {
                SleepStageBar(overview = overview, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    text = "系统未返回完整的深睡、浅睡、REM 与清醒时长，比例图已隐藏。",
                    style = PhoneType.Body,
                    color = PhoneColor.TextDim
                )
            }

            Row {
                StageMetric(
                    label = "深睡",
                    minutes = overview.deepMinutes,
                    available = overview.deepAvailable,
                    color = PhoneColor.SleepDeep,
                    modifier = Modifier.weight(1f)
                )
                StageMetric(
                    label = "浅睡",
                    minutes = overview.lightMinutes,
                    available = overview.lightAvailable,
                    color = PhoneColor.SleepLight,
                    modifier = Modifier.weight(1f)
                )
            }
            Row {
                StageMetric(
                    label = "REM",
                    minutes = overview.remMinutes,
                    available = overview.remAvailable,
                    color = PhoneColor.SleepRem,
                    modifier = Modifier.weight(1f)
                )
                StageMetric(
                    label = "清醒",
                    minutes = overview.awakeMinutes,
                    available = overview.awakeAvailable,
                    color = PhoneColor.SleepAwake,
                    modifier = Modifier.weight(1f)
                )
            }

            if (overview.durationAvailable && overview.stageTotalMinutes() > 0L &&
                abs(overview.totalDurationMinutes - overview.stageTotalMinutes()) > 10L
            ) {
                Text(
                    text = "系统总时长 ${PhoneFormat.minutesHuman(overview.totalDurationMinutes.toInt())}，" +
                        "阶段合计 ${PhoneFormat.minutesHuman(overview.stageTotalMinutes().toInt())}；" +
                        "两组原始字段不一致，均按原值展示。",
                    style = PhoneType.Caption,
                    color = PhoneColor.Warning
                )
            }

            val health = ArrayList<String>()
            if (overview.spo2Available) health.add("平均血氧 ${overview.spo2AveragePercent}%")
            if (overview.heartRateAvailable) health.add("睡眠心率 ${overview.heartRateBenchmarkBpm} bpm")
            if (overview.breathRateAvailable) {
                health.add("呼吸 " + String.format(Locale.CHINA, "%.1f 次/分", overview.breathRateBenchmarkPerMinute))
            }
            Text(
                text = if (health.isEmpty()) "血氧、心率与呼吸数据未返回" else health.joinToString(" · "),
                style = PhoneType.Body,
                color = PhoneColor.TextDim
            )
            Text(
                text = "${overview.sessionCount} 段睡眠 · ${overview.rawStageCount} 个系统原始阶段",
                style = PhoneType.Caption,
                color = PhoneColor.Hint
            )
        }
    }
}

@Composable
private fun SleepHero(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(PhoneRadius.card))
            .background(PhoneColor.SurfaceHigh)
            .padding(horizontal = PhoneSpace.sm, vertical = PhoneSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = PhoneType.Title,
            color = PhoneColor.Text,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            style = PhoneType.Caption,
            color = PhoneColor.TextDim,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StageMetric(
    label: String,
    minutes: Long,
    available: Boolean,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val text = PhoneUiContract.sleepStageDescription(
        label,
        if (available) PhoneFormat.minutesHuman(minutes.toInt()) else "--"
    )
    Row(
        modifier = modifier
            .height(PhoneSize.touchTarget)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(PhoneRadius.pill))
                .background(color)
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(PhoneSpace.sm))
        Text(text = text, style = PhoneType.Label, color = PhoneColor.Text)
    }
}

internal fun stageTypeName(type: Int, rawType: Int): String =
    PhoneSleepTimeline.typeName(type, rawType)

internal fun overviewSignature(overview: PhoneSleepOverview): String =
    "${overview.timestamp}:${overview.totalDurationMinutes}"
