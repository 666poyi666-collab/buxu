package com.poyi.watchintervals.phone.ui

import com.poyi.watchintervals.phone.connection.ConnectionState

/**
 * 手机端 UI 领域模型。
 *
 * 这些类型是 UI 层唯一消费的数据形状;底层仍以 JSONObject 持久化,转换集中在
 * ViewModel 内完成。状态中不出现 Color 等 Android 图形对象,颜色语义一律用
 * 枚举表达,由组件层映射,便于 JVM 测试直接断言状态而不依赖渲染。
 */

/** 状态语义色,由组件层映射为具体色值。 */
enum class Tone { Positive, Caution, Negative, Neutral, Progress }

/** 阶段类型。 */
enum class StageKind(val wire: String) {
    RUN("RUN"), WALK("WALK"), REST("REST");

    companion object {
        fun fromWire(value: String): StageKind =
            entries.firstOrNull { it.wire == value } ?: RUN

        fun next(value: String): StageKind {
            val current = fromWire(value)
            return entries[(current.ordinal + 1) % entries.size]
        }
    }
}

/** 阶段目标单位。 */
enum class StageUnit(val wire: String) {
    DISTANCE("DISTANCE"), TIME("TIME");

    companion object {
        fun fromWire(value: String): StageUnit =
            if (value == DISTANCE.wire) DISTANCE else TIME
    }
}

data class StageDraft(
    val kind: StageKind,
    val unit: StageUnit,
    val target: Int
)

data class PlanSummary(
    val id: String,
    val name: String,
    val groupId: String,
    val groupName: String,
    val requirement: String,
    val stages: List<StageDraft>,
    val summary: String,
    val sequence: String,
    val sortOrder: Int,
    val selectedOnPhone: Boolean,
    val currentOnWatch: Boolean
)

data class PlanGroupBlock(
    val id: String,
    val name: String,
    val plans: List<PlanSummary>
)

/** 计划页三级导航:列表、详情、编辑器。 */
sealed interface PlanRoute {
    data object Library : PlanRoute
    data class Detail(val planId: String) : PlanRoute
    data object Editor : PlanRoute
}

data class PlanDraft(
    val id: String = "",
    val name: String = "",
    val groupId: String = "",
    val group: String = "",
    val requirement: String = "",
    val stages: List<StageDraft> = emptyList(),
    val dirty: Boolean = false
)

data class PlanState(
    val route: PlanRoute = PlanRoute.Library,
    val groups: List<PlanGroupBlock> = emptyList(),
    val ungrouped: List<PlanSummary> = emptyList(),
    val draft: PlanDraft = PlanDraft(),
    /** 手表当前正在使用的计划 id。 */
    val watchCurrentId: String = "",
    val watchCurrentName: String = "",
    val watchCurrentGroup: String = "",
    val savedCount: Int = 0
)

/** 手表实时训练状态。字段缺失一律以 null 表达,不得用 0 冒充读数。 */
data class LiveWorkout(
    val state: String = "",
    val planState: String = "",
    val activeDurationMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val currentPaceSecondsPerKm: Int = 0,
    val avgPaceSecondsPerKm: Long = 0L,
    val heartRate: Int = 0,
    val heartRateZone: Int = 0,
    val averageHeartRate: Int = 0,
    val maxHeartRate: Int = 0,
    val cadenceSpm: Int = 0,
    val calories: Int = 0,
    val elevationGainMeters: Double = 0.0,
    val steps: Int = 0,
    val splitCount: Int = 0,
    val stageName: String = "",
    val stageNumber: Int = 0,
    val stageCount: Int = 0
) {
    val running: Boolean get() = state == "RUNNING"
    val paused: Boolean get() = state == "PAUSED"
    val preparing: Boolean get() = state == "PREPARING"
    val planCompleted: Boolean get() = planState == "COMPLETED"
    val hasWorkout: Boolean get() = state.isNotEmpty()

    /** 环进度以已完成阶段数为准;计划完成后为满环。 */
    val ringProgress: Float
        get() = if (planCompleted) 1f
        else if (stageCount > 0) ((stageNumber - 1).coerceAtLeast(0).toFloat() / stageCount)
        else 0f
}

/** 训练页可执行操作,由手表真实状态推导,不提供状态盲操作。 */
enum class WorkoutAction { Start, Pause, Resume, Stop }

data class WorkoutState(
    val live: LiveWorkout? = null,
    val error: String? = null,
    val actions: List<WorkoutAction> = emptyList(),
    val notice: String? = null,
    val transportReady: Boolean = false
)

data class WorkoutSummary(
    val id: String,
    val startedAt: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val steps: Int,
    val averageHeartRate: Int,
    val routePointCount: Int
)

data class HistoryState(
    val records: List<WorkoutSummary> = emptyList(),
    val summary: String = "",
    val error: String? = null,
    val loadingDetail: Boolean = false
)

/** 单晚睡眠:总览与时间线配对,图表直接消费。 */
data class SleepNightUi(
    val overview: com.poyi.watchintervals.phone.PhoneSleepOverview,
    val timeline: com.poyi.watchintervals.phone.PhoneSleepTimeline
)

/** 睡眠页状态。空态与不可用态显式分离,刷新失败不得把内容替换成空白错误页。 */
sealed interface SleepState {
    data object Idle : SleepState
    data object Loading : SleepState
    data class Empty(val title: String, val detail: String) : SleepState
    data class Ready(
        val nights: List<SleepNightUi>,
        val week: com.poyi.watchintervals.phone.PhoneSleepWeek,
        val cached: Boolean,
        val summary: String,
        val note: String?
    ) : SleepState
}

data class ConnectionUi(
    val state: ConnectionState = ConnectionState.IDLE,
    val label: String = "尚未连接",
    val tone: Tone = Tone.Neutral,
    val paired: Boolean = false,
    val transportReady: Boolean = false,
    val pairingCode: String = "",
    val primaryTransport: String? = null,
    val bulkTransport: String? = null,
    val lastSeenAt: Long = 0L,
    val lastSuccessfulRequestAt: Long = 0L,
    val rssi: Int = 0,
    val mtu: Int = 0,
    val pendingOperations: Int = 0,
    val notificationsSubscribed: Boolean = false,
    val lanAvailable: Boolean = false,
    val lastDisconnectReason: String = ""
)

data class SyncUi(
    val busy: Boolean = false,
    val message: String = "",
    val lastSyncLabel: String = "",
    val tone: Tone = Tone.Neutral
)

/** 云端配置与链路状态,直接反映 Cloud V3 可用与否。 */
data class CloudUi(
    val endpoint: String = "",
    val configured: Boolean = false,
    val tokenSaved: Boolean = false,
    val statusLabel: String = "未配置",
    val tone: Tone = Tone.Neutral
)

data class SetupUi(
    val visible: Boolean = false,
    val lanHost: String = "",
    val pairingCode: String = "",
    val cloud: CloudUi = CloudUi()
)

data class PhoneUiState(
    val section: Int = 0,
    val connection: ConnectionUi = ConnectionUi(),
    val sync: SyncUi = SyncUi(),
    val setup: SetupUi = SetupUi(),
    val plan: PlanState = PlanState(),
    val workout: WorkoutState = WorkoutState(),
    val history: HistoryState = HistoryState(),
    val sleep: SleepState = SleepState.Idle
)

/** 一次性事件:提示、跳转、权限请求等,不进入持久状态。 */
sealed interface PhoneEvent {
    data class Toast(val message: String) : PhoneEvent
    data class OpenWorkoutDetail(val payload: String) : PhoneEvent
    data object RequestLocationPermission : PhoneEvent
    data object RequestBluetoothPermission : PhoneEvent
}
