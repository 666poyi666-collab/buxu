package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poyi.watchintervals.phone.PhoneFormat
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneCardStyle
import com.poyi.watchintervals.phone.ui.components.PhoneEmptyState
import com.poyi.watchintervals.phone.ui.components.PhonePageHeader
import com.poyi.watchintervals.phone.ui.components.PhoneMetric
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 训练历史。列表只呈现可扫读的核心指标,完整轨迹与样本在点开详情后读取。 */
@Composable
fun HistoryScreen(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
    val history = state.history
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PhoneSpace.xl,
            end = PhoneSpace.xl,
            top = PhoneSpace.sm,
            bottom = PhoneSize.navigation + 38.dp
        ),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
    ) {
        item {
            PhonePageHeader(
                title = "训练历史",
                subtitle = ""
            )
        }
        item {
            Text(
                text = history.error ?: history.summary.ifBlank { "连接后读取" },
                style = PhoneType.Body,
                color = if (history.error != null) PhoneColor.Danger else PhoneColor.TextDim
            )
        }
        if (history.records.isEmpty() && history.error == null) {
            item {
                PhoneEmptyState(
                    title = "还没有训练记录",
                    detail = "完成一次训练后，这里会出现可复盘的完整数据。"
                )
            }
        }
        items(history.records, key = { it.id }) { record ->
            HistoryRow(
                record = record,
                onClick = {
                    viewModel.clearHistoryError()
                    viewModel.openWorkoutDetail(record.id)
                }
            )
        }
    }
}

@Composable
private fun HistoryRow(record: WorkoutSummary, onClick: () -> Unit) {
    val date = dateLabel(record.startedAt)
    val distance = PhoneFormat.distance(record.distanceMeters)
    val duration = PhoneFormat.duration(record.durationMs)
    val pace = PhoneFormat.pace(record.durationMs, record.distanceMeters)
    val description = PhoneUiContract.historyRowDescription(date, distance, duration)
    PhoneCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickableWithRole(onClick = onClick, contentDescription = description),
        style = PhoneCardStyle.Large
    ) {
        Column(
            modifier = Modifier.padding(PhoneSpace.lg),
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = date, style = PhoneType.Subhead, color = PhoneColor.Text)
                if (record.routePointCount > 0) {
                    Text(
                        text = "GPS 轨迹",
                        style = PhoneType.Caption,
                        color = PhoneColor.Stand
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                PhoneMetric(
                    label = "距离",
                    value = distance,
                    valueColor = PhoneColor.Exercise,
                    modifier = Modifier.weight(1f)
                )
                PhoneMetric(
                    label = "用时",
                    value = duration,
                    modifier = Modifier.weight(1f)
                )
                PhoneMetric(
                    label = "平均配速",
                    value = pace,
                    valueColor = PhoneColor.Stand,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${record.steps} 步",
                    style = PhoneType.Caption,
                    color = PhoneColor.TextDim
                )
                Text(
                    text = if (record.averageHeartRate > 0) "♥ ${record.averageHeartRate} bpm" else "-- bpm",
                    style = PhoneType.Caption,
                    color = if (record.averageHeartRate > 0) PhoneColor.Danger else PhoneColor.TextDim
                )
                Text(
                    text = "${record.routePointCount} 采样点",
                    style = PhoneType.Caption,
                    color = PhoneColor.TextDim
                )
            }
        }
    }
}

private fun dateLabel(startedAt: Long): String =
    if (startedAt <= 0L) "时间未返回"
    else SimpleDateFormat("MM月dd日  HH:mm", Locale.CHINA).format(Date(startedAt))
