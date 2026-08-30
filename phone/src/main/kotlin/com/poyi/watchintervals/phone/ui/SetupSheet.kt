package com.poyi.watchintervals.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.poyi.watchintervals.phone.PhoneCloudSetupSpec
import com.poyi.watchintervals.phone.ui.components.PhoneAction
import com.poyi.watchintervals.phone.ui.components.PhoneBadge
import com.poyi.watchintervals.phone.ui.components.PhoneButton
import com.poyi.watchintervals.phone.ui.components.PhoneCard
import com.poyi.watchintervals.phone.ui.components.PhoneCardStyle
import com.poyi.watchintervals.phone.ui.components.PhoneDivider
import com.poyi.watchintervals.phone.ui.components.PhoneInput
import com.poyi.watchintervals.phone.ui.components.PhoneStatusDot
import com.poyi.watchintervals.phone.ui.icon.PhoneIcons
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType

/**
 * 连接与云同步设置。
 *
 * 这是唯一使用玻璃功能层的展开面板;内容卡保持实色,不在此叠玻璃。
 * 云端文案直接引用 [PhoneCloudSetupSpec],保证设置页不会出现已退役的 V2 说法。
 */
@Composable
fun SetupSheet(
    state: PhoneUiState,
    viewModel: PhoneViewModel,
    modifier: Modifier = Modifier
) {
    BackHandler { viewModel.toggleSetup(false) }
    var token by remember { mutableStateOf("") }
    var advancedConnectionVisible by remember { mutableStateOf(false) }
    var cloudConfigurationVisible by remember(state.setup.cloud.configured) {
        mutableStateOf(!state.setup.cloud.configured)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xBE000000))
            .clickable { viewModel.toggleSetup(false) }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .imePadding()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
        ) {
            PhoneCard(
                modifier = Modifier.fillMaxWidth(),
                style = PhoneCardStyle.Large
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(PhoneSpace.lg),
                    verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "设备与同步",
                                style = PhoneType.Title,
                                color = PhoneColor.Text,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .size(PhoneSize.touchTarget)
                                    .clickable { viewModel.toggleSetup(false) }
                                    .semantics { contentDescription = "关闭连接与云同步设置" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = PhoneIcons.Close,
                                    contentDescription = null,
                                    tint = PhoneColor.TextDim,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    item {
                        SectionLabel(text = "手表")
                        ConnectionFactBlock(state = state)
                        Spacer(modifier = Modifier.height(PhoneSpace.md))
                        if (!state.connection.paired) {
                            PhoneInput(
                                value = state.setup.pairingCode,
                                onValueChange = { viewModel.updatePairingCode(it) },
                                label = "手表上的 6 位配对码",
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                )
                            )
                            Spacer(modifier = Modifier.height(PhoneSpace.sm))
                        }
                        PhoneButton(
                            text = if (state.connection.state == com.poyi.watchintervals.phone.connection.ConnectionState.SCANNING ||
                                state.connection.state == com.poyi.watchintervals.phone.connection.ConnectionState.CONNECTING_BLE
                            ) "正在连接…" else if (state.connection.transportReady) "重新检测连接" else "连接手表",
                            onClick = { viewModel.reconnectWatch() },
                            action = if (state.connection.transportReady) PhoneAction.Secondary else PhoneAction.Primary,
                            enabled = state.connection.state != com.poyi.watchintervals.phone.connection.ConnectionState.SCANNING &&
                                state.connection.state != com.poyi.watchintervals.phone.connection.ConnectionState.CONNECTING_BLE,
                            modifier = Modifier.fillMaxWidth(),
                            contentDescription = "重新连接手表",
                            icon = PhoneIcons.Sync
                        )
                        Spacer(modifier = Modifier.height(PhoneSpace.xs))
                        PhoneButton(
                            text = if (advancedConnectionVisible) "收起高级连接" else "高级连接",
                            onClick = { advancedConnectionVisible = !advancedConnectionVisible },
                            action = PhoneAction.Ghost,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (advancedConnectionVisible) {
                            Spacer(modifier = Modifier.height(PhoneSpace.xs))
                            PhoneInput(
                                value = state.setup.lanHost,
                                onValueChange = { viewModel.updateLanHost(it) },
                                label = "LAN 地址"
                            )
                        }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "云端同步",
                                style = PhoneType.Subhead,
                                color = PhoneColor.Text,
                                modifier = Modifier.weight(1f)
                            )
                            PhoneBadge(
                                text = state.setup.cloud.statusLabel,
                                container = PhoneColor.SurfaceHigh,
                                content = if (state.setup.cloud.configured) PhoneColor.Success else PhoneColor.TextDim
                            )
                        }
                        PhoneButton(
                            text = if (cloudConfigurationVisible) "收起云端配置" else "配置云端同步",
                            onClick = { cloudConfigurationVisible = !cloudConfigurationVisible },
                            action = PhoneAction.Ghost,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!state.setup.cloud.configured) {
                            Text(
                                text = "设备云凭据缺失 · ChatGPT 的计划不会到达手机或手表",
                                style = PhoneType.Caption,
                                color = PhoneColor.Danger,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (cloudConfigurationVisible) {
                            Spacer(modifier = Modifier.height(PhoneSpace.xs))
                            PhoneInput(
                                value = state.setup.cloud.endpoint,
                                onValueChange = { viewModel.updateCloudEndpoint(it) },
                                label = PhoneCloudSetupSpec.ENDPOINT_HINT,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Uri
                                )
                            )
                            Spacer(modifier = Modifier.height(PhoneSpace.sm))
                            PhoneInput(
                                value = token,
                                onValueChange = { token = it },
                                label = if (state.setup.cloud.tokenSaved) {
                                    "已安全保存设备 token"
                                } else {
                                    PhoneCloudSetupSpec.TOKEN_HINT
                                },
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(modifier = Modifier.height(PhoneSpace.sm))
                            PhoneButton(
                                text = PhoneCloudSetupSpec.SAVE_ACTION,
                                onClick = { viewModel.saveCloud(token) },
                                action = PhoneAction.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                icon = PhoneIcons.Cloud
                            )
                            Spacer(modifier = Modifier.height(PhoneSpace.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = PhoneIcons.Cloud,
                                    contentDescription = null,
                                    tint = if (state.setup.cloud.configured) {
                                        PhoneColor.Success
                                    } else {
                                        PhoneColor.TextDim
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(PhoneSpace.sm))
                                Text(
                                    text = PhoneCloudSetupSpec.SECURITY_NOTE,
                                    style = PhoneType.Caption,
                                    color = PhoneColor.TextDim
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionFactBlock(state: PhoneUiState) {
    val connection = state.connection
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhoneRadius.control))
            .background(PhoneColor.SurfaceHigh)
            .padding(PhoneSpace.md),
        verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneStatusDot(color = toneColor(connection.tone))
            Spacer(modifier = Modifier.width(PhoneSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = connection.label, style = PhoneType.Subhead, color = PhoneColor.Text)
                Text(
                    text = if (connection.paired) "已配对 · 设备身份已锁定" else "尚未完成配对",
                    style = PhoneType.Caption,
                    color = PhoneColor.TextDim
                )
            }
            PhoneBadge(
                text = connection.primaryTransport ?: "未连接",
                container = PhoneColor.Surface,
                content = toneColor(connection.tone)
            )
        }
        PhoneDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ConnectionValue(
                label = "批量链路",
                value = if (connection.lanAvailable) "LAN" else "未验证",
                tone = if (connection.lanAvailable) Tone.Positive else Tone.Neutral
            )
            ConnectionValue(
                label = "最近成功",
                value = connection.lastSuccessfulRequestAt.toTimeLabel(),
                tone = Tone.Neutral
            )
            ConnectionValue(
                label = "待处理",
                value = connection.pendingOperations.toString(),
                tone = if (connection.pendingOperations > 0) Tone.Caution else Tone.Neutral
            )
        }
        if (connection.lastDisconnectReason.isNotBlank() && !connection.transportReady) {
            Text(
                text = "上次中断：${connection.lastDisconnectReason}",
                style = PhoneType.Caption,
                color = PhoneColor.Warning
            )
        }
    }
}

@Composable
private fun ConnectionValue(label: String, value: String, tone: Tone) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = PhoneType.BodyStrong, color = toneColor(tone))
        Text(text = label, style = PhoneType.Caption, color = PhoneColor.TextDim)
    }
}

private fun Long.toTimeLabel(): String {
    if (this <= 0L) return "暂无"
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(this))
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = PhoneType.Subhead, color = PhoneColor.Text)
}
