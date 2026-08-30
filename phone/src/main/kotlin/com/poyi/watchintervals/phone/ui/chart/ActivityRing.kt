package com.poyi.watchintervals.phone.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 训练活动环。
 *
 * 进度随已完成的阶段数增长;计划完成后为满环。渐变从起点色经中间色回到起点色,
 * 使任意进度下环的收尾都是同一色相,避免低进度出现突兀的断色。
 */
@Composable
fun ActivityRing(
    progress: Float,
    modifier: Modifier = Modifier,
    startColor: Color,
    midColor: Color,
    trackColor: Color = startColor.copy(alpha = 0.16f),
    contentDescription: String? = null,
    content: @Composable () -> Unit = {}
) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else Modifier
                )
        ) {
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val diameter = max(1f, size.minDimension - stroke.width)
            val topLeft = Offset(inset, inset)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
            if (fraction > 0f) {
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(startColor, midColor, startColor),
                            center = Offset(size.width / 2f, size.height / 2f)
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                }
            }
        }
        content()
    }
}
