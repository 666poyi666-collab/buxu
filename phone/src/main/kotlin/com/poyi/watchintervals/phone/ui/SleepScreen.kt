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

    val bedTimeStr = if (overview.bedtime > 0L) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(overview.bedtime))
    } else if (timeline.available()) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeline.startTime))
    } else null

    val wakeTimeStr = if (overview.wakeTime > 0L) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(overview.wakeTime))
    } else if (timeline.available()) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timeline.endTime))
    } else null

    PhoneCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PhoneSpace.lg),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.md)
        ) {
            // 头部：日期与入睡/醒来起止
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "$date 睡眠", style = PhoneType.Headline, color = PhoneColor.Text)
                if (bedTimeStr != null && wakeTimeStr != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(PhoneRadius.pill))
                            .background(PhoneColor.SurfaceHigh)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "入睡 $bedTimeStr  ·  醒来 $wakeTimeStr",
                            style = PhoneType.Caption,
                            color = PhoneColor.TextDim
                        )
                    }
                }
            }

            // 核心双指标：评分与总时长
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

            // 分段睡眠明细（支持第一段、第二段/午休）
            if (overview.sessions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
                    Text(text = "分段睡眠明细", style = PhoneType.Subhead, color = PhoneColor.Text)
                    overview.sessions.forEach { session ->
                        SleepSessionCard(session = session, totalCount = overview.sessions.size)
                    }
                }
            }

            // 阶段时间线
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

            // 阶段构成比例条与统计
            Text(text = "全晚阶段构成", style = PhoneType.Subhead, color = PhoneColor.Text)
            if (overview.stageBreakdownAvailable) {
                SleepStageBar(overview = overview, modifier = Modifier.fillMaxWidth())
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

            // 生理指标卡片（心率范围、呼吸率、血氧）
            SleepVitalsSection(overview = overview)

            Text(
                text = "${overview.sessionCount} 段睡眠 · ${overview.rawStageCount} 个系统原始阶段",
                style = PhoneType.Caption,
                color = PhoneColor.Hint
            )
        }
    }
}

@Composable
private fun SleepSessionCard(
    session: com.poyi.watchintervals.phone.PhoneSleepOverview.SessionItem,
    totalCount: Int
) {
    val sessionName = if (totalCount == 1) {
        "主睡眠"
    } else if (session.index == 1) {
        "第一段睡眠 · 主睡眠"
    } else {
        "第 ${session.index} 段睡眠 · 午休 / 小憩"
    }

    val timeSpan = if (session.startTime > 0L && session.endTime > 0L) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(session.startTime)) +
            " – " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(session.endTime))
    } else {
        ""
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoneRadius.card))
            .background(PhoneColor.SurfaceHigh)
            .padding(PhoneSpace.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = sessionName, style = PhoneType.BodyStrong, color = PhoneColor.Text)
                if (timeSpan.isNotBlank()) {
                    Text(text = timeSpan, style = PhoneType.Caption, color = PhoneColor.TextDim)
                }
            }

            val durationText = if (session.durationMinutes > 0L) {
                PhoneFormat.minutesHuman(session.durationMinutes.toInt())
            } else {
                "--"
            }
            Text(text = "时长：$durationText", style = PhoneType.Caption, color = PhoneColor.Text)

            // 该段睡眠阶段构成摘要
            val parts = ArrayList<String>()
            if (session.deepMinutes > 0L) parts.add("深睡 ${session.deepMinutes}分")
            if (session.lightMinutes > 0L) parts.add("浅睡 ${session.lightMinutes}分")
            if (session.remMinutes > 0L) parts.add("REM ${session.remMinutes}分")
            if (session.awakeMinutes > 0L) parts.add("清醒 ${session.awakeMinutes}分")
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString("  |  "),
                    style = PhoneType.Caption,
                    color = PhoneColor.TextDim
                )
            }
        }
    }
}

@Composable
private fun SleepVitalsSection(overview: com.poyi.watchintervals.phone.PhoneSleepOverview) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoneRadius.card))
            .background(PhoneColor.SurfaceHigh)
            .padding(PhoneSpace.md),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.xs)
    ) {
        Text(text = "生理指标", style = PhoneType.Subhead, color = PhoneColor.Text)

        // 心率
        val hrText = if (overview.heartRateAvailable) {
            val range = if (overview.heartRateMinBpm > 0 && overview.heartRateMaxBpm > 0) {
                "（范围 ${overview.heartRateMinBpm} – ${overview.heartRateMaxBpm} bpm）"
            } else ""
            "心率：${overview.heartRateBenchmarkBpm} bpm $range"
        } else {
            "心率：--"
        }
        Text(text = hrText, style = PhoneType.Caption, color = PhoneColor.Text)

        // 呼吸率
        val brText = if (overview.breathRateAvailable) {
            val range = if (overview.breathRateMinPerMinute > 0 && overview.breathRateMaxPerMinute > 0) {
                String.format(Locale.CHINA, "（范围 %.1f – %.1f 次/分）", overview.breathRateMinPerMinute, overview.breathRateMaxPerMinute)
            } else ""
            String.format(Locale.CHINA, "呼吸：%.1f 次/分 %s", overview.breathRateBenchmarkPerMinute, range)
        } else {
            "呼吸：--"
        }
        Text(text = brText, style = PhoneType.Caption, color = PhoneColor.Text)

        // 血氧
        val spo2Text = if (overview.spo2Available) {
            "平均血氧：${overview.spo2AveragePercent}%"
        } else {
            "平均血氧：--"
        }
        Text(text = spo2Text, style = PhoneType.Caption, color = PhoneColor.Text)
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
