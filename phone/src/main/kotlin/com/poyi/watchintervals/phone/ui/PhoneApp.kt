package com.poyi.watchintervals.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.poyi.watchintervals.phone.PhoneNavigationSpec
import com.poyi.watchintervals.phone.PhoneSymbol
import com.poyi.watchintervals.phone.ui.components.PhoneStatusDot
import com.poyi.watchintervals.phone.ui.icon.PhoneIcons
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneTheme
import com.poyi.watchintervals.phone.ui.theme.PhoneType

/**
 * 手机端应用根。
 *
 * 结构固定为三层:内容层(浅色实心卡)、浮动功能层(底部导航与连接设置)、
 * 顶部品牌与状态带。内容始终延伸到底栏之后,保证最后一项可完整滚出。
 */
@Composable
fun PhoneApp(viewModel: PhoneViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PhoneTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PhoneColor.Canvas)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                PhoneTopBar(
                    state = state,
                    onSync = { viewModel.sync() },
                    onSetup = { viewModel.toggleSetup(!state.setup.visible) }
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (state.section) {
                        1 -> WorkoutScreen(state = state, viewModel = viewModel)
                        2 -> HistoryScreen(state = state, viewModel = viewModel)
                        3 -> SleepScreen(state = state, viewModel = viewModel)
                        else -> PlanScreen(state = state, viewModel = viewModel)
                    }
                }
            }
            PhoneBottomNavigation(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                selected = state.section,
                onSelect = { index ->
                    if (index == state.section) return@PhoneBottomNavigation
                    viewModel.selectSection(index)
                }
            )
            if (state.setup.visible) {
                SetupSheet(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun PhoneTopBar(
    state: PhoneUiState,
    onSync: () -> Unit,
    onSetup: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhoneSpace.pageMargin)
            .padding(vertical = PhoneSpace.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = PhoneIcons.Brand,
            contentDescription = "步序",
            tint = PhoneColor.Move,
            modifier = Modifier.size(PhoneSize.brandMark)
        )
        Spacer(modifier = Modifier.width(PhoneSpace.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PhoneSize.touchTarget)
                .semantics {
                    contentDescription = "连接状态：${state.connection.label}，打开设备设置"
                }
                .clickable(role = Role.Button) { onSetup() },
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "步序",
                style = PhoneType.Subhead,
                color = PhoneColor.Text
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneStatusDot(color = toneColor(state.connection.tone), modifier = Modifier.size(8.dp))
                Spacer(modifier = Modifier.width(PhoneSpace.xs))
                Text(
                    text = state.connection.label +
                        if (!state.cloudConfigured()) " · 云端未连接"
                        else if (state.connection.pendingOperations > 0) {
                        " · 待处理 ${state.connection.pendingOperations}"
                    } else "",
                    style = PhoneType.Caption,
                    color = if (!state.cloudConfigured() || state.sync.tone == Tone.Negative) {
                        PhoneColor.Danger
                    } else {
                        PhoneColor.TextDim
                    },
                    maxLines = 1
                )
            }
        }
        IconButton(
            onClick = onSync,
            enabled = !state.sync.busy,
            modifier = Modifier
                .size(PhoneSize.touchTarget)
                .semantics { contentDescription = "立即同步手表数据" }
        ) {
            Icon(
                imageVector = PhoneIcons.Sync,
                contentDescription = null,
                tint = if (state.sync.busy) PhoneColor.TextDim else PhoneColor.Move,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onSetup,
            modifier = Modifier
                .size(PhoneSize.touchTarget)
                .semantics { contentDescription = "打开连接与云同步设置" }
        ) {
            Icon(
                imageVector = PhoneIcons.Settings,
                contentDescription = null,
                tint = PhoneColor.TextDim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun PhoneUiState.cloudConfigured(): Boolean =
    setup.cloud.configured && setup.cloud.tokenSaved

@Composable
private fun PhoneBottomNavigation(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = PhoneNavigationSpec.ITEMS
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PhoneSize.navigation)
            .background(PhoneColor.Navigation)
            .border(1.dp, PhoneColor.NavigationLine)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PhoneSpace.sm, vertical = PhoneSpace.xxs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val active = index == selected
                NavigationItem(
                    icon = iconForSymbol(item.symbol),
                    label = item.label,
                    accessibilityLabel = item.accessibilityLabel,
                    selected = active,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavigationItem(
    icon: ImageVector,
    label: String,
    accessibilityLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) PhoneColor.NavigationText else PhoneColor.NavigationMuted
    Column(
        modifier = modifier
            .heightIn(min = PhoneSize.touchTarget)
            .clickable { onClick() }
            .semantics {
                contentDescription = PhoneUiContract.destinationDescription(
                    accessibilityLabel, selected
                )
            }
            .padding(vertical = PhoneSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .background(if (selected) PhoneColor.Move else Color.Transparent)
        )
        Spacer(modifier = Modifier.height(PhoneSpace.xxs))
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = PhoneType.Caption,
            color = tint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun toneColor(tone: Tone): androidx.compose.ui.graphics.Color = when (tone) {
    Tone.Positive -> PhoneColor.Success
    Tone.Caution -> PhoneColor.Warning
    Tone.Negative -> PhoneColor.Danger
    Tone.Progress -> PhoneColor.Stand
    Tone.Neutral -> PhoneColor.TextDim
}

internal fun iconForSymbol(symbol: PhoneSymbol): ImageVector = when (symbol) {
    PhoneSymbol.PLAN -> PhoneIcons.Plan
    PhoneSymbol.WORKOUT -> PhoneIcons.Workout
    PhoneSymbol.HISTORY -> PhoneIcons.History
    PhoneSymbol.SLEEP -> PhoneIcons.Sleep
    PhoneSymbol.BACK -> PhoneIcons.Back
    PhoneSymbol.LOCATION -> PhoneIcons.Location
    PhoneSymbol.BRAND -> PhoneIcons.Brand
}
