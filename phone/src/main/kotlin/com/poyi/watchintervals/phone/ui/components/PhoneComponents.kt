package com.poyi.watchintervals.phone.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.poyi.watchintervals.phone.ui.theme.PhoneColor
import com.poyi.watchintervals.phone.ui.theme.PhoneRadius
import com.poyi.watchintervals.phone.ui.theme.PhoneSize
import com.poyi.watchintervals.phone.ui.theme.PhoneSpace
import com.poyi.watchintervals.phone.ui.theme.PhoneType

/**
 * 操作层级。
 *
 * 同一屏内只允许出现一个 Primary,避免等权按钮堆叠;危险操作必须使用 Danger
 * 并且始终保留文字标签,不能只靠颜色传达语义。
 */
enum class PhoneAction { Primary, Secondary, Danger, Ghost }

@Composable
fun PhoneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    action: PhoneAction = PhoneAction.Secondary,
    enabled: Boolean = true,
    contentDescription: String? = null,
    icon: ImageVector? = null
) {
    val palette = actionPalette(action)
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = PhoneSize.touchTarget)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        enabled = enabled,
        shape = RoundedCornerShape(PhoneRadius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.container,
            contentColor = palette.content,
            disabledContainerColor = palette.container.copy(alpha = 0.38f),
            disabledContentColor = palette.content.copy(alpha = 0.5f)
        ),
        contentPadding = PaddingValues(horizontal = PhoneSpace.lg, vertical = PhoneSpace.sm),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(PhoneSpace.sm))
        }
        Text(text = text, style = PhoneType.BodyStrong)
    }
}

private data class ActionPalette(val container: Color, val content: Color)

@Composable
private fun actionPalette(action: PhoneAction): ActionPalette = when (action) {
    PhoneAction.Primary -> ActionPalette(PhoneColor.Move, PhoneColor.OnAccent)
    PhoneAction.Secondary -> ActionPalette(PhoneColor.SurfaceHigh, PhoneColor.Text)
    PhoneAction.Danger -> ActionPalette(PhoneColor.FillDanger, PhoneColor.Danger)
    PhoneAction.Ghost -> ActionPalette(Color.Transparent, PhoneColor.Move)
}

/**
 * 内容卡。三段圆角分别服务列表项、常规卡片和大容器,不在卡内叠玻璃。
 */
enum class PhoneCardStyle { List, Standard, Large }

@Composable
fun PhoneCard(
    modifier: Modifier = Modifier,
    style: PhoneCardStyle = PhoneCardStyle.Standard,
    containerColor: Color = PhoneColor.Surface,
    borderColor: Color = PhoneColor.Border,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(
        when (style) {
            PhoneCardStyle.List -> PhoneRadius.card
            PhoneCardStyle.Standard -> PhoneRadius.cardLarge
            PhoneCardStyle.Large -> PhoneRadius.sheet
        }
    )
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp
    ) { content() }
}

/** 页面标题保持紧凑,把首屏留给计划和训练数据。 */
@Composable
fun PhonePageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = PhoneType.Display,
            color = PhoneColor.Text,
            modifier = Modifier.heightIn(min = 34.dp)
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = PhoneType.Body,
                color = PhoneColor.TextDim,
                modifier = Modifier.heightIn(min = 22.dp)
            )
        }
    }
}

/** 分区小标题,层级低于页面大标题。 */
@Composable
fun PhoneSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PhoneType.Subhead,
        color = PhoneColor.Text,
        modifier = modifier.padding(vertical = PhoneSpace.sm)
    )
}

/**
 * 数据块。等宽数字保证刷新时宽度不跳动,标签与数值分离以便 TalkBack 完整朗读。
 */
@Composable
fun PhoneMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PhoneColor.Text,
    labelColor: Color = PhoneColor.TextDim
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = PhoneType.Caption, color = labelColor)
        Text(
            text = value,
            style = PhoneType.Headline,
            color = valueColor,
            maxLines = 1
        )
    }
}

/**
 * 状态徽章。选中与警示状态同时用底色、文字和形状表达,不单独依赖颜色。
 */
@Composable
fun PhoneBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = PhoneColor.Surface,
    content: Color = PhoneColor.Success
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(PhoneRadius.chip))
            .background(container)
            .padding(horizontal = PhoneSpace.sm, vertical = PhoneSpace.xxs),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = PhoneType.Caption, color = content)
    }
}

/** 连接状态点。颜色只用于扫读,语义由相邻文字承载。 */
@Composable
fun PhoneStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(RoundedCornerShape(PhoneRadius.pill))
            .background(color)
    )
}

@Composable
fun PhoneInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhoneSize.input),
        textStyle = PhoneType.Body,
        label = { Text(text = label, style = PhoneType.Caption) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        shape = RoundedCornerShape(PhoneRadius.control),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        colors = outlinedInputColors()
    )
}

@Composable
private fun outlinedInputColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PhoneColor.Exercise,
    unfocusedBorderColor = PhoneColor.Border,
    disabledBorderColor = PhoneColor.Border,
    errorBorderColor = PhoneColor.Danger,
    focusedContainerColor = PhoneColor.SurfaceHigh,
    unfocusedContainerColor = PhoneColor.SurfaceHigh,
    disabledContainerColor = PhoneColor.SurfaceDeep,
    errorContainerColor = PhoneColor.FillDanger,
    focusedTextColor = PhoneColor.Text,
    unfocusedTextColor = PhoneColor.Text,
    focusedLabelColor = PhoneColor.Exercise,
    unfocusedLabelColor = PhoneColor.Hint,
    cursorColor = PhoneColor.Exercise
)

/**
 * 空态。标题与说明分离,可选主操作;不得用空白错误页替代内容。
 */
@Composable
fun PhoneEmptyState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    PhoneCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PhoneSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PhoneSpace.sm)
        ) {
            Text(
                text = title,
                style = PhoneType.Subhead,
                color = PhoneColor.Text,
                textAlign = TextAlign.Center
            )
            Text(
                text = detail,
                style = PhoneType.Caption,
                color = PhoneColor.TextDim,
                textAlign = TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(PhoneSpace.xs))
                PhoneButton(text = actionText, onClick = onAction, action = PhoneAction.Primary)
            }
        }
    }
}

/**
 * 可点击行。触控目标不低于 48dp,可访问名称由调用方给出完整中文描述。
 */
@Composable
fun PhoneClickableRow(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val container = if (selected) PhoneColor.FillSelected else PhoneColor.SurfaceHigh
    val stroke = if (selected) PhoneColor.Exercise else PhoneColor.Border
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PhoneSize.touchTarget)
            .clip(RoundedCornerShape(PhoneRadius.control))
            .background(container, RoundedCornerShape(PhoneRadius.control))
            .border(1.dp, stroke, RoundedCornerShape(PhoneRadius.control))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button
            ) { onClick() }
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = PhoneSpace.md, vertical = PhoneSpace.sm)
    ) { content() }
}

/** 阶段类型语义色。跑步、快走、休息三态在双端保持同一色义。 */
fun stageKindColor(kind: String): Color = when (kind) {
    "WALK" -> PhoneColor.Stand
    "REST" -> PhoneColor.Caution
    else -> PhoneColor.Exercise
}

fun stageKindFill(kind: String): Color = when (kind) {
    "WALK" -> PhoneColor.FillWalk
    "REST" -> PhoneColor.FillRest
    else -> PhoneColor.FillRun
}

/**
 * 水平分隔线,用于卡片内部的弱分组。
 */
@Composable
fun PhoneDivider(modifier: Modifier = Modifier, color: Color = PhoneColor.Border) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/**
 * 并排等宽布局,用于阶段指标这类需要严格对齐的两列数据。
 */
@Composable
fun PhoneSplitRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = PhoneSpace.sm,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f)) { left() }
        Spacer(modifier = Modifier.width(spacing))
        Box(modifier = Modifier.weight(1f)) { right() }
    }
}
