package com.poyi.watchintervals.phone.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poyi.watchintervals.phone.PhoneFormat
import com.poyi.watchintervals.phone.PhoneSleepOverview
import com.poyi.watchintervals.phone.PhoneSleepTimeline
import com.poyi.watchintervals.phone.PhoneSleepWeek
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val ChartLabel = TextStyle(fontSize = 11.sp, color = PhoneColor.TextDim)
private val ChartValue = TextStyle(fontSize = 11.sp, color = PhoneColor.Text)
private val UnknownStage = Color(0xFF9AA5B1)

private fun stageColor(type: Int): Color = when (type) {
    PhoneSleepTimeline.DEEP -> PhoneColor.SleepDeep
    PhoneSleepTimeline.LIGHT -> PhoneColor.SleepLight
    PhoneSleepTimeline.REM -> PhoneColor.SleepRem
    PhoneSleepTimeline.AWAKE -> PhoneColor.SleepAwake
    else -> UnknownStage
}

private fun clockFormat(value: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(value))

private fun dayFormat(value: Long): String =
    SimpleDateFormat("M/d", Locale.CHINA).format(Date(value))

/**
 * 近 7 晚总时长趋势。
 *
 * 只绘制已同步缓存中真实存在的夜晚;缺失的夜晚留空,不补造数据。
 * 柱顶标注小时数,底部标注日期,颜色只用于扫读,语义由文字承载。
 */
@Composable
fun SleepWeekTrend(
    week: PhoneSleepWeek,
    modifier: Modifier = Modifier
) {
    val nights = week.nights
    val maximum = max(60L, week.maximumMinutes()).toFloat()
    val measurer = rememberTextMeasurer()
    val description = remember(nights) {
        if (nights.isEmpty()) {
            "近 7 晚趋势：暂无数据"
        } else {
            val parts = nights.map { night ->
                "${dayFormat(night.timestamp)} ${PhoneFormat.minutesHuman(night.durationMinutes.toInt())}"
            }
            "近 7 晚睡眠时长趋势：" + parts.joinToString("，")
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(PhoneSize.sleepChart)
            .semantics { contentDescription = description }
    ) {
        if (nights.isEmpty()) return@Canvas
        val labelHeight = 22.dp.toPx()
        val valueHeight = 18.dp.toPx()
        val plotTop = valueHeight
        val plotBottom = size.height - labelHeight
        val plotHeight = plotBottom - plotTop
        val slot = size.width / nights.size
        val barWidth = slot * 0.46f

        // Eight-hour reference line: the only fixed marker, so a short night reads as short.
        val referenceMinutes = 8f * 60f
        if (referenceMinutes <= maximum) {
            val referenceY = plotBottom - (referenceMinutes / maximum) * plotHeight
            drawLine(
                color = PhoneColor.Border,
                start = Offset(0f, referenceY),
                end = Offset(size.width, referenceY),
                strokeWidth = 1.dp.toPx()
            )
        }

        nights.forEachIndexed { index, night ->
            val fraction = (night.durationMinutes.toFloat() / maximum).coerceIn(0f, 1f)
            val barHeight = fraction * plotHeight
            val top = plotBottom - barHeight
            val left = slot * index + (slot - barWidth) / 2f
            drawRoundRect(
                color = PhoneColor.SleepLight,
                topLeft = Offset(left, top),
                size = Size(barWidth, max(2.dp.toPx(), barHeight)),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            val hours = night.durationMinutes / 60f
            val value = String.format(Locale.CHINA, "%.1fh", hours)
            drawCenteredText(measurer, value, left + barWidth / 2f, plotTop - 6.dp.toPx(), ChartValue)
            drawCenteredText(
                measurer,
                dayFormat(night.timestamp),
                left + barWidth / 2f,
                plotBottom + 4.dp.toPx(),
                ChartLabel
            )
        }
    }
}

/**
 * 单晚阶段时间线。
 *
 * 自上而下为清醒、REM、浅睡、深睡。段与段之间只有间隔不超过 5 分钟时才连线,
 * 多 session 空档与厂商未知阶段保留原样,不把缺失数据补成连续色块。
 */
@Composable
fun SleepStageTimeline(
    timeline: PhoneSleepTimeline,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val segments = timeline.segments
    val span = max(1L, timeline.endTime - timeline.startTime)
    val description = remember(timeline) {
        if (!timeline.available()) {
            "阶段时间线：系统未返回有效阶段"
        } else {
            val rows = intArrayOf(
                PhoneSleepTimeline.AWAKE,
                PhoneSleepTimeline.REM,
                PhoneSleepTimeline.LIGHT,
                PhoneSleepTimeline.DEEP
            ).map { type ->
                "${PhoneSleepTimeline.typeName(type, type)} ${PhoneFormat.minutesHuman(
                    timeline.durationMinutes(type).toInt()
                )}"
            }
            "阶段时间线：" + rows.joinToString("，") + "，共 ${segments.size} 段"
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(PhoneSize.sleepChart)
            .semantics { contentDescription = description }
    ) {
        if (!timeline.available()) return@Canvas
        val axisHeight = 20.dp.toPx()
        val plotHeight = size.height - axisHeight
        val rowHeight = plotHeight / 4f
        val rowInset = 3.dp.toPx()
        val rowTypes = intArrayOf(
            PhoneSleepTimeline.AWAKE,
            PhoneSleepTimeline.REM,
            PhoneSleepTimeline.LIGHT,
            PhoneSleepTimeline.DEEP
        )
        val rowIndex = { type: Int -> rowTypes.indexOf(type) }

        segments.forEachIndexed { index, segment ->
            val row = rowIndex(segment.type)
            val left = ((segment.startTime - timeline.startTime).toFloat() / span) * size.width
            val width = ((segment.endTime - segment.startTime).toFloat() / span) * size.width
            val top = row * rowHeight + rowInset
            val height = rowHeight - rowInset * 2f
            drawRoundRect(
                color = stageColor(segment.type),
                topLeft = Offset(left, top),
                size = Size(max(1.5.dp.toPx(), width), height),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
            // Connect only genuinely adjacent samples; a real gap must stay a gap.
            val next = segments.getOrNull(index + 1)
            if (next != null && next.startTime - segment.endTime <= 5 * 60_000L) {
                val nextRow = rowIndex(next.type)
                if (nextRow != row) {
                    drawLine(
                        color = PhoneColor.Border,
                        start = Offset(left + width, top + height / 2f),
                        end = Offset(left + width, nextRow * rowHeight + rowHeight / 2f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
        }

        val axisY = plotHeight + 2.dp.toPx()
        drawText(measurer, clockFormat(timeline.startTime), Offset(0f, axisY), ChartLabel)
        val middle = clockFormat(timeline.startTime + span / 2L)
        drawCenteredText(measurer, middle, size.width / 2f, axisY, ChartLabel)
        val endText = clockFormat(timeline.endTime)
        val endWidth = measurer.measure(AnnotatedString(endText), ChartLabel).size.width
        drawText(measurer, endText, Offset(size.width - endWidth, axisY), ChartLabel)
    }
}

/**
 * 单晚阶段构成条。仅在系统返回完整四阶段时绘制,否则调用方显示文字说明。
 */
@Composable
fun SleepStageBar(
    overview: PhoneSleepOverview,
    modifier: Modifier = Modifier
) {
    val parts = longArrayOf(
        overview.deepMinutes,
        overview.lightMinutes,
        overview.remMinutes,
        overview.awakeMinutes
    )
    val total = max(1L, parts.sum())
    val colors = arrayOf(
        PhoneColor.SleepDeep,
        PhoneColor.SleepLight,
        PhoneColor.SleepRem,
        PhoneColor.SleepAwake
    )
    val description = remember(overview) {
        val labels = arrayOf("深睡", "浅睡", "REM", "清醒")
        labels.mapIndexed { index, label ->
            "$label ${PhoneFormat.minutesHuman(parts[index].toInt())}"
        }.joinToString("，")
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .semantics { contentDescription = "阶段构成：$description" }
    ) {
        var left = 0f
        val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        drawRoundRect(
            color = PhoneColor.SurfaceDeep,
            topLeft = Offset(0f, 0f),
            size = size,
            cornerRadius = radius
        )
        parts.forEachIndexed { index, minutes ->
            if (minutes <= 0L) return@forEachIndexed
            val width = (minutes.toFloat() / total) * size.width
            drawRoundRect(
                color = colors[index],
                topLeft = Offset(left, 0f),
                size = Size(width, size.height),
                cornerRadius = radius
            )
            left += width
        }
    }
}

private fun DrawScope.drawCenteredText(
    measurer: TextMeasurer,
    text: String,
    centerX: Float,
    top: Float,
    style: TextStyle
) {
    val width = measurer.measure(AnnotatedString(text), style).size.width
    drawText(measurer, text, Offset(centerX - width / 2f, top), style)
}
