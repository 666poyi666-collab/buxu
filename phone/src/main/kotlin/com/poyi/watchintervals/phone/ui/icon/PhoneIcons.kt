package com.poyi.watchintervals.phone.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 步序原创图标集。
 *
 * 统一规范:24x24 逻辑视口,2dp 描边,圆头圆角连接,图形落在 2dp 光学边距内。
 * 全部为原创几何,不使用 Unicode 字形、平台字体图标或任何第三方符号库。
 * 路径颜色固定为黑色,由调用方通过 tint 着色,因此同一几何可服务任意语义色。
 */
object PhoneIcons {

    /**
     * 品牌标记:两段阶梯式往返阶段汇入一枚前进箭头。
     * 与启动器标志共享同一"间歇路线"语义,在 24dp 视口内简化为可读轮廓。
     */
    val Brand: ImageVector by lazy {
        mixedIcon(
            "Brand",
            "M4,7 H12 A2,2 0 0 1 12,11 H8" to false,
            "M8,13 H14 A2,2 0 0 1 14,17 H6" to false,
            "M17,13 L21,15.5 L17,18 Z" to true
        )
    }

    /** 计划:带条目标记的阶段清单。 */
    val Plan: ImageVector by lazy {
        strokedIcon(
            "Plan",
            "M4.01,6 H4.01",
            "M4.01,12 H4.01",
            "M4.01,18 H4.01",
            "M8,6 H20",
            "M8,12 H20",
            "M8,18 H14"
        )
    }

    /** 训练:跑步姿态人形。 */
    val Workout: ImageVector by lazy {
        strokedIcon(
            "Workout",
            "M13.2,5 A1.8,1.8 0 1 1 16.8,5 A1.8,1.8 0 1 1 13.2,5",
            "M15,6.9 V10.6",
            "M15,8 L11.8,6.8",
            "M15,8 L18,9.3",
            "M15,10.6 L12,14.6 L13.9,18.6",
            "M15,10.6 L18,13.6 L17.2,18.6"
        )
    }

    /** 历史:计时表盘。 */
    val History: ImageVector by lazy {
        strokedIcon(
            "History",
            "M12,4 A8,8 0 1 1 12,20 A8,8 0 1 1 12,4",
            "M12,7.5 V12 L15,13.8"
        )
    }

    /** 睡眠:弯月。 */
    val Sleep: ImageVector by lazy {
        strokedIcon(
            "Sleep",
            "M20.5,13.5 A8.5,8.5 0 1 1 10.8,4.2 A7,7 0 0 0 20.5,13.5 Z"
        )
    }

    /** 返回:左向人字。 */
    val Back: ImageVector by lazy {
        strokedIcon("Back", "M15,5 L8,12 L15,19")
    }

    /** 前进/进入详情。 */
    val Forward: ImageVector by lazy {
        strokedIcon("Forward", "M9,5 L16,12 L9,19")
    }

    /** 定位:针形标记。 */
    val Location: ImageVector by lazy {
        strokedIcon(
            "Location",
            "M12,21 C12,21 18.5,14.8 18.5,10.2 A6.5,6.5 0 1 0 5.5,10.2 C5.5,14.8 12,21 12,21 Z",
            "M12,7.8 A2.4,2.4 0 1 1 12,12.6 A2.4,2.4 0 1 1 12,7.8 Z"
        )
    }

    /** 添加。 */
    val Add: ImageVector by lazy {
        strokedIcon("Add", "M12,5 V19", "M5,12 H19")
    }

    /** 编辑。 */
    val Edit: ImageVector by lazy {
        strokedIcon("Edit", "M4,20 H8 L20,8 A2.5,2.5 0 0 0 16.5,4.5 L4.5,16.5 Z")
    }

    /** 删除。 */
    val Delete: ImageVector by lazy {
        strokedIcon(
            "Delete",
            "M5,7 H19",
            "M9,7 V5 A1,1 0 0 1 10,4 H14 A1,1 0 0 1 15,5 V7",
            "M6.5,7 L7.5,20 A1,1 0 0 0 8.5,21 H15.5 A1,1 0 0 0 16.5,20 L17.5,7"
        )
    }

    /** 更多操作。 */
    val More: ImageVector by lazy {
        strokedIcon("More", "M5,12 H5.01", "M12,12 H12.01", "M19,12 H19.01")
    }

    /** 开始/继续。 */
    val Play: ImageVector by lazy {
        mixedIcon("Play", "M8,5 L19,12 L8,19 Z" to true)
    }

    /** 暂停。 */
    val Pause: ImageVector by lazy {
        strokedIcon("Pause", "M9,6 V18", "M15,6 V18")
    }

    /** 停止。 */
    val Stop: ImageVector by lazy {
        strokedIcon("Stop", "M7,7 H17 V17 H7 Z")
    }

    /** 确认。 */
    val Check: ImageVector by lazy {
        strokedIcon("Check", "M5,12 L10,17 L19,7")
    }

    /** 上移。 */
    val MoveUp: ImageVector by lazy {
        strokedIcon("MoveUp", "M12,19 V6", "M6,12 L12,6 L18,12")
    }

    /** 下移。 */
    val MoveDown: ImageVector by lazy {
        strokedIcon("MoveDown", "M12,5 V18", "M6,12 L12,18 L18,12")
    }

    /** 同步。 */
    val Sync: ImageVector by lazy {
        strokedIcon(
            "Sync",
            "M20,12 A8,8 0 0 1 12,20 A8,8 0 0 1 5.5,15",
            "M4,12 A8,8 0 0 1 12,4 A8,8 0 0 1 18.5,9",
            "M18.5,4 V9.5 H13",
            "M5.5,20 V14.5 H11"
        )
    }

    /** 设置。 */
    val Settings: ImageVector by lazy {
        strokedIcon(
            "Settings",
            "M12,15.4 A3.4,3.4 0 1 1 12,8.6 A3.4,3.4 0 1 1 12,15.4",
            "M19.3,14.6 A1.7,1.7 0 0 0 19.5,16.4 L20.7,17.2 A0.9,0.9 0 0 1 21,18.5 L20.2,19.8 A0.9,0.9 0 0 1 18.9,20.1 L17.6,19.4 A1.7,1.7 0 0 0 15.9,19.6 L15.6,21.1 A0.9,0.9 0 0 1 14.7,21.9 H13 A0.9,0.9 0 0 1 12.1,21.1 L11.8,19.6 A1.7,1.7 0 0 0 10.1,19.4 L8.8,20.1 A0.9,0.9 0 0 1 7.5,19.8 L6.7,18.5 A0.9,0.9 0 0 1 7,17.2 L8.3,16.4 A1.7,1.7 0 0 0 8.5,14.6 L8.5,13.4 A1.7,1.7 0 0 0 8.3,11.6 L7,10.8 A0.9,0.9 0 0 1 6.7,9.5 L7.5,8.2 A0.9,0.9 0 0 1 8.8,7.9 L10.1,8.6 A1.7,1.7 0 0 0 11.8,8.4 L12.1,6.9 A0.9,0.9 0 0 1 13,6.1 H14.7 A0.9,0.9 0 0 1 15.6,6.9 L15.9,8.4 A1.7,1.7 0 0 0 17.6,8.6 L18.9,7.9 A0.9,0.9 0 0 1 20.2,8.2 L21,9.5 A0.9,0.9 0 0 1 20.7,10.8 L19.4,11.6 A1.7,1.7 0 0 0 19.3,13.4 Z"
        )
    }

    /** 云端。 */
    val Cloud: ImageVector by lazy {
        strokedIcon(
            "Cloud",
            "M7,18 A4,4 0 0 1 7,10 A5.5,5.5 0 0 1 17.8,11.2 A3.6,3.6 0 0 1 17.5,18 Z"
        )
    }

    /** 关闭。 */
    val Close: ImageVector by lazy {
        strokedIcon("Close", "M6,6 L18,18", "M18,6 L6,18")
    }

    /** 心率。 */
    val Heart: ImageVector by lazy {
        strokedIcon(
            "Heart",
            "M12,20 C12,20 4,15.2 4,9.8 A4.3,4.3 0 0 1 12,7 A4.3,4.3 0 0 1 20,9.8 C20,15.2 12,20 12,20 Z"
        )
    }
}

private const val VIEWPORT = 24f
private const val STROKE_WIDTH = 2f

/**
 * 把 SVG 路径数据写入 [PathBuilder]。
 *
 * [PathBuilder] 没有公开的注入入口,而图标几何以路径字符串维护更便于与手表端共享同一份
 * 数值定义,因此这里显式完成 PathNode 到构建器调用的映射。
 */
private fun PathBuilder.applyPathData(data: String) {
    for (node in addPathNodes(data)) {
        when (node) {
            is PathNode.MoveTo -> moveTo(node.x, node.y)
            is PathNode.RelativeMoveTo -> moveToRelative(node.dx, node.dy)
            is PathNode.LineTo -> lineTo(node.x, node.y)
            is PathNode.RelativeLineTo -> lineToRelative(node.dx, node.dy)
            is PathNode.HorizontalTo -> horizontalLineTo(node.x)
            is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
            is PathNode.VerticalTo -> verticalLineTo(node.y)
            is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy)
            is PathNode.CurveTo ->
                curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
            is PathNode.RelativeCurveTo ->
                curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
            is PathNode.ReflectiveCurveTo -> reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeReflectiveCurveTo ->
                reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
            is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeQuadTo -> quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
            is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
            is PathNode.RelativeReflectiveQuadTo -> reflectiveQuadToRelative(node.dx, node.dy)
            is PathNode.ArcTo -> arcTo(
                node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, node.arcStartX, node.arcStartY
            )
            is PathNode.RelativeArcTo -> arcToRelative(
                node.horizontalEllipseRadius, node.verticalEllipseRadius, node.theta,
                node.isMoreThanHalf, node.isPositiveArc, node.arcStartDx, node.arcStartDy
            )
            is PathNode.Close -> close()
        }
    }
}

/**
 * 描边图标。零长度线段配合圆头端点用于绘制圆点标记。
 */
private fun strokedIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT
    ).apply {
        for (data in paths) {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) { applyPathData(data) }
        }
    }.build()

/**
 * 混合图标:true 表示该路径为实心,false 为描边。
 * 用于同一图形内既有轮廓又有正形的组合(例如品牌标记的箭头)。
 */
private fun mixedIcon(name: String, vararg paths: Pair<String, Boolean>): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT
    ).apply {
        for ((data, filled) in paths) {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = if (filled) 0f else STROKE_WIDTH,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) { applyPathData(data) }
        }
    }.build()
