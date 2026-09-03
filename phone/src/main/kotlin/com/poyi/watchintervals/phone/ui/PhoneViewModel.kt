package com.poyi.watchintervals.phone.ui

import android.app.Application
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.poyi.watchintervals.phone.CloudSnapshotSync
import com.poyi.watchintervals.phone.CloudSyncCredentials
import com.poyi.watchintervals.phone.PhoneCloudSetupSpec
import com.poyi.watchintervals.phone.PhoneFormat
import com.poyi.watchintervals.phone.PhoneHealthSync
import com.poyi.watchintervals.phone.PhonePlanLibrary
import com.poyi.watchintervals.phone.PhonePlanUiModel
import com.poyi.watchintervals.phone.PhoneSleepOverview
import com.poyi.watchintervals.phone.PhoneSleepRepository
import com.poyi.watchintervals.phone.PhoneSleepSync
import com.poyi.watchintervals.phone.PhoneSleepTimeline
import com.poyi.watchintervals.phone.PhoneSleepWeek
import com.poyi.watchintervals.phone.PhoneSleepSyncWorker
import com.poyi.watchintervals.phone.PhoneSyncOutbox
import com.poyi.watchintervals.phone.PhoneSyncPolicy
import com.poyi.watchintervals.phone.WatchClient
import com.poyi.watchintervals.phone.connection.ConnectionState
import com.poyi.watchintervals.phone.connection.WatchConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端唯一状态持有者。
 *
 * 业务与传输能力全部复用既有 Java 实现,本类只负责编排、状态归约和线程切换:
 * 所有网络与磁盘访问都在单并发的 IO 作用域内串行执行,状态更新回到主线程。
 * 手表 WorkoutService 仍是训练状态的唯一权威,本类只读快照并发送命令。
 */
class PhoneViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    /**
     * 单并发 IO 作用域。同步、计划上传和睡眠读取必须串行,避免同一传输被重复占用。
     */
    private val ioScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _state = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PhoneEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val syncInFlight = AtomicBoolean(false)
    private var connection: WatchConnectionManager? = null
    private var previousConnectionState: ConnectionState? = null
    private var livePollingJob: Job? = null
    private var retryJob: Job? = null

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolving = false

    private val connectionObserver = WatchConnectionManager.Observer { snapshot ->
        viewModelScope.launch {
            val paired = connection?.identity()?.isPaired() ?: false
            _state.update { current ->
                current.copy(
                    connection = connectionUi(snapshot, paired, current.setup.pairingCode),
                    setup = current.setup.copy(
                        pairingCode = if (paired) "" else current.setup.pairingCode
                    )
                )
            }
            val shouldSync = PhoneSyncPolicy.shouldAutoSync(
                previousConnectionState, snapshot.state, syncInFlight.get()
            )
            previousConnectionState = snapshot.state
            if (shouldSync) sync()
        }
    }

    init {
        viewModelScope.launch {
            val prefs = app.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE)
            val cloud = CloudSyncCredentials.load(app)
            val connection = WatchConnectionManager.get(app)
            this@PhoneViewModel.connection = connection
            val paired = connection.identity().isPaired()
            val snapshot = connection.snapshot()
            _state.update { current ->
                current.copy(
                    connection = connectionUi(snapshot, paired, current.setup.pairingCode),
                    setup = current.setup.copy(
                        lanHost = prefs.getString("host", "") ?: "",
                        pairingCode = if (paired) "" else connection.identity().pairingCode(),
                        cloud = CloudUi(
                            endpoint = cloud.endpoint,
                            configured = cloud.configured(),
                            tokenSaved = cloud.configured(),
                            statusLabel = if (cloud.configured()) "已配置 Cloud V3" else "未配置",
                            tone = if (cloud.configured()) Tone.Positive else Tone.Neutral
                        )
                    ),
                    sync = current.sync.copy(lastSyncLabel = lastSyncLabel())
                )
            }
            connection.observe(connectionObserver)
            refreshPlans()
        }
    }

    // ---------------------------------------------------------------- 生命周期

    fun onResume() {
        if (_state.value.section == 1) startLivePolling()
    }

    fun onPause() {
        stopLivePolling()
    }

    override fun onCleared() {
        // ioScope 是独立作用域,不跟随 viewModelScope 取消,必须显式取消,
        // 否则 ViewModel 销毁后仍可能有同步请求占用传输。
        ioScope.cancel()
        retryJob?.cancel()
        stopLivePolling()
        stopDiscovery()
        connection?.removeObserver(connectionObserver)
        super.onCleared()
    }

    // ---------------------------------------------------------------- 导航

    fun selectSection(index: Int) {
        stopLivePolling()
        _state.update { it.copy(section = index, setup = it.setup.copy(visible = false)) }
        if (index == 1) startLivePolling()
        if (index == 3) loadSleep()
    }

    // ---------------------------------------------------------------- 同步

    fun sync() {
        if (!syncInFlight.compareAndSet(false, true)) {
            _state.update {
                it.copy(sync = it.sync.copy(message = "同步正在进行，完成后会自动更新"))
            }
            return
        }
        ioScope.launch {
            val started = System.currentTimeMillis()
            try {
                val connected = connection
                if (connected == null) throw IllegalStateException("连接管理器尚未就绪")
                val prefs = app.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("host", _state.value.setup.lanHost.trim())
                    .remove("code")
                    .apply()

                setSync(true, PhoneSyncPolicy.progressLabel(0, 5, "准备同步"), Tone.Progress)
                val pairing = _state.value.setup.pairingCode.trim()
                if (!connected.identity().isPaired() && pairing.length != 6) {
                    throw IllegalArgumentException("请输入手表上的 6 位配对码")
                }
                connected.configurePairing(pairing)
                connected.configureLan(_state.value.setup.lanHost.trim(), pairing)
                // BLE 优先但不是必须:已验证的 LAN 可绕过失败的 BLE 连接,不阻塞整次同步。
                try {
                    connected.connect().get(25, TimeUnit.SECONDS)
                } catch (bleError: Exception) {
                    if (!connected.snapshot().lanAvailable) {
                        throw IllegalStateException("蓝牙连接失败，且局域网不可达；请靠近手表或连接同一 Wi-Fi", bleError)
                    }
                }

                setSync(true, PhoneSyncPolicy.progressLabel(1, 5, "验证手表"), Tone.Progress)
                val status = JSONObject(connected.requestBlocking("GET", "/v1/status", "", 20_000L))
                val expectedId = prefs.getString("watch_device_id", "") ?: ""
                val actualId = status.optString("deviceId")
                if (expectedId.isNotEmpty() && expectedId != actualId) {
                    throw IllegalStateException("发现的设备身份与已配对手表不一致")
                }
                if (expectedId.isEmpty() && actualId.isNotEmpty()) {
                    prefs.edit().putString("watch_device_id", actualId).apply()
                }

                val cloudConfigured = CloudSyncCredentials.readyForCloudV3(app)
                setSync(
                    true,
                    PhoneSyncPolicy.progressLabel(2, 5,
                        if (cloudConfigured) "读取云端计划" else "云端未连接"),
                    if (cloudConfigured) Tone.Progress else Tone.Caution
                )
                val cloudSynced = cloudConfigured && CloudSnapshotSync.sync(app)

                setSync(true, PhoneSyncPolicy.progressLabel(3, 5, "投影到手表"), Tone.Progress)
                PhoneSyncOutbox.ensureCurrentLibrary(app)
                val projection = PhoneSyncOutbox.drain(app, connected)
                val watchPlansSynced = "synced" == projection.optString("state")
                val plan = JSONObject(connected.requestBlocking("GET", "/v1/plan/profile", "", 20_000L))
                val history = JSONArray(connected.requestBlocking("GET", "/v1/history", "", 20_000L))

                setSync(true, PhoneSyncPolicy.progressLabel(4, 5, "刷新睡眠"), Tone.Progress)
                var sleepCandidate: JSONObject? = null
                var sleepHadRecords = false
                try {
                    val value = PhoneSleepSync.fetchRecent(connected, 31)
                    if ("ready" == value.optString("state")) {
                        sleepHadRecords = (value.optJSONArray("records")?.length() ?: 0) > 0
                        sleepCandidate = PhoneSleepRepository.mergeAndSave(app, value, System.currentTimeMillis())
                        CloudSnapshotSync.syncSleepAsync(app)
                    }
                } catch (sleepError: Exception) {
                    android.util.Log.w("PhoneViewModel", "Sleep refresh did not block the main sync", sleepError)
                }
                // Pull the manufacturer health summary (steps, activity, heart-rate stats) and cache
                // it. It is uploaded to the cloud so ChatGPT can query the same real system data the
                // watch exposes. A read failure never cancels the rest of the sync.
                try {
                    val health = PhoneHealthSync.fetchRecent(connected, 31)
                    if ("ready" == health.optString("state")) {
                        CloudSnapshotSync.syncHealthAsync(app)
                    }
                } catch (healthError: Exception) {
                    android.util.Log.w("PhoneViewModel", "Health refresh did not block the main sync", healthError)
                }

                val completedAt = System.currentTimeMillis()
                rememberLastSync(completedAt)
                val syncedSleep = sleepCandidate
                val hadRecords = sleepHadRecords
                val planState = planStateFrom(plan)
                val historyState = historyStateFrom(history)
                withContext(Dispatchers.Main) {
                    _state.update { current ->
                        current.copy(
                            plan = planState,
                            history = historyState,
                            sleep = sleepStateFrom(syncedSleep, cached = true, note =
                                if (hadRecords) null else "手表本次没有返回新记录，保留上次数据"),
                            sync = current.sync.copy(
                                busy = false,
                                message = when {
                                    !cloudConfigured ->
                                        "手表已同步 · 云端未连接，ChatGPT 修改不会下发"
                                    !cloudSynced ->
                                        "手表已同步 · 云端同步失败，已安排重试"
                                    !watchPlansSynced ->
                                        "云端和手机已同步 · 等待手表确认"
                                    else ->
                                        "云端、手机、手表已一致 · " +
                                            SimpleDateFormat("HH:mm", Locale.CHINA)
                                                .format(Date(completedAt))
                                },
                                lastSyncLabel = lastSyncLabel(),
                                tone = if (cloudSynced && watchPlansSynced) {
                                    Tone.Positive
                                } else {
                                    Tone.Caution
                                }
                            )
                        )
                    }
                }
                _events.emit(PhoneEvent.RequestLocationPermission)
            } catch (error: Exception) {
                val reason = userError(error)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(sync = it.sync.copy(busy = false, message = "同步未完成 · $reason · 连接恢复后会重试", tone = Tone.Caution))
                    }
                }
                PhoneSleepSyncWorker.schedule(app)
                retryJob?.cancel()
                retryJob = viewModelScope.launch {
                    delay(30_000L)
                    if (_state.value.connection.transportReady) sync()
                }
            } finally {
                syncInFlight.set(false)
            }
        }
    }

    private suspend fun setSync(busy: Boolean, message: String, tone: Tone) {
        withContext(Dispatchers.Main) {
            _state.update { it.copy(sync = it.sync.copy(busy = busy, message = message, tone = tone)) }
        }
    }

    // ---------------------------------------------------------------- 计划

    fun refreshPlans() {
        ioScope.launch {
            val planState = planStateFrom(null)
            withContext(Dispatchers.Main) { _state.update { it.copy(plan = planState) } }
        }
    }

    private fun planStateFrom(watchProfile: JSONObject?): PlanState {
        val current = _state.value.plan
        val library = PhonePlanLibrary.load(app)
        val watchId = watchProfile?.optString("id") ?: current.watchCurrentId
        val watchName = watchProfile?.optString("name")?.trim().orEmpty()
        val watchGroup = watchProfile?.optString("group")?.trim().orEmpty()
        val selected = library.optString("selectedPlanId")
        val plans = library.optJSONArray("plans")
        val groups = library.optJSONArray("groups")

        val summaries = ArrayList<PlanSummary>()
        for (index in 0 until (plans?.length() ?: 0)) {
            val item = plans?.optJSONObject(index) ?: continue
            summaries.add(planSummaryFrom(item, library, selected, watchId))
        }

        val blocks = ArrayList<PlanGroupBlock>()
        val rendered = HashSet<String>()
        for (index in 0 until (groups?.length() ?: 0)) {
            val group = groups?.optJSONObject(index) ?: continue
            val groupId = group.optString("id")
            val members = summaries.filter { it.groupId == groupId }
            blocks.add(PlanGroupBlock(groupId, group.optString("name"), members))
            members.forEach { rendered.add(it.id) }
        }
        val ungrouped = summaries.filter { it.id !in rendered }

        // 远端刷新只更新列表,不得投影进正在编辑的草稿;详情目标若已被删除则回退到列表。
        val route = if (current.route is PlanRoute.Detail &&
            summaries.none { it.id == current.route.planId }
        ) {
            PlanRoute.Library
        } else {
            current.route
        }
        return current.copy(
            route = route,
            groups = blocks,
            ungrouped = ungrouped,
            savedCount = summaries.size,
            watchCurrentId = watchId,
            watchCurrentName = watchName.ifEmpty { current.watchCurrentName },
            watchCurrentGroup = watchGroup.ifEmpty { current.watchCurrentGroup }
        )
    }

    private fun planSummaryFrom(
        item: JSONObject,
        library: JSONObject,
        selectedId: String,
        watchId: String
    ): PlanSummary {
        val stages = stageListFrom(item.optJSONArray("stages"))
        return PlanSummary(
            id = item.optString("id"),
            name = item.optString("name"),
            groupId = item.optString("groupId"),
            groupName = PhonePlanLibrary.groupName(library, item.optString("groupId")),
            requirement = item.optString("requirement"),
            stages = stages,
            summary = PhonePlanUiModel.summary(item.optJSONArray("stages")),
            sequence = PhonePlanUiModel.compactSequence(item.optJSONArray("stages")),
            sortOrder = item.optInt("sortOrder"),
            selectedOnPhone = item.optString("id") == selectedId,
            currentOnWatch = item.optString("id") == watchId
        )
    }

    private fun stageListFrom(array: JSONArray?): List<StageDraft> {
        val result = ArrayList<StageDraft>()
        for (index in 0 until (array?.length() ?: 0)) {
            val stage = array?.optJSONObject(index) ?: continue
            result.add(
                StageDraft(
                    kind = StageKind.fromWire(stage.optString("kind")),
                    unit = StageUnit.fromWire(stage.optString("unit")),
                    target = stage.optInt("target", 1).coerceAtLeast(1)
                )
            )
        }
        return result
    }

    fun openPlanLibrary() {
        _state.update { it.copy(plan = it.plan.copy(route = PlanRoute.Library)) }
        refreshPlans()
    }

    fun openPlanDetail(planId: String) {
        _state.update { it.copy(plan = it.plan.copy(route = PlanRoute.Detail(planId))) }
    }

    fun editPlan(planId: String) {
        ioScope.launch {
            val library = PhonePlanLibrary.load(app)
            val item = findPlan(library, planId) ?: return@launch
            val draft = PlanDraft(
                id = item.optString("id"),
                name = item.optString("name"),
                groupId = item.optString("groupId"),
                group = PhonePlanLibrary.groupName(library, item.optString("groupId")),
                requirement = item.optString("requirement"),
                stages = stageListFrom(item.optJSONArray("stages")),
                dirty = false
            )
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(plan = it.plan.copy(route = PlanRoute.Editor, draft = draft))
                }
            }
        }
    }

    fun createPlan() {
        val group = _state.value.plan.groups.firstOrNull()
        _state.update {
            it.copy(
                plan = it.plan.copy(
                    route = PlanRoute.Editor,
                    draft = PlanDraft(
                        groupId = group?.id.orEmpty(),
                        group = group?.name.orEmpty(),
                        stages = listOf(StageDraft(StageKind.RUN, StageUnit.DISTANCE, 1000)),
                        dirty = true
                    )
                )
            )
        }
    }

    fun createPlanInGroup(groupId: String, groupName: String) {
        ioScope.launch {
            val library = PhonePlanLibrary.load(app)
            val plans = library.optJSONArray("plans")
            var day = 1
            for (index in 0 until (plans?.length() ?: 0)) {
                val item = plans?.optJSONObject(index) ?: continue
                if (groupId == item.optString("groupId")) day++
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        plan = it.plan.copy(
                            route = PlanRoute.Editor,
                            draft = PlanDraft(
                                name = "第${day}天",
                                groupId = groupId,
                                group = groupName,
                                requirement = "设置当天独立的跑步、快走与恢复内容。",
                                stages = listOf(StageDraft(StageKind.RUN, StageUnit.TIME, 1200)),
                                dirty = true
                            )
                        )
                    )
                }
            }
        }
    }

    fun updateDraft(name: String? = null, group: String? = null, requirement: String? = null) {
        _state.update { current ->
            val draft = current.plan.draft
            current.copy(
                plan = current.plan.copy(
                    draft = draft.copy(
                        name = name ?: draft.name,
                        group = group ?: draft.group,
                        requirement = requirement ?: draft.requirement,
                        dirty = true
                    )
                )
            )
        }
    }

    fun selectDraftGroup(groupId: String, groupName: String) {
        _state.update { current ->
            val draft = current.plan.draft
            current.copy(plan = current.plan.copy(draft = draft.copy(
                groupId = groupId,
                group = groupName,
                dirty = true
            )))
        }
    }

    fun addStage(kind: StageKind, unit: StageUnit, target: Int) {
        _state.update { current ->
            val stages = current.plan.draft.stages + StageDraft(kind, unit, target)
            current.copy(plan = current.plan.copy(draft = current.plan.draft.copy(stages = stages, dirty = true)))
        }
    }

    fun cycleStageKind(index: Int) {
        mutateStage(index) { stage ->
            val next = StageKind.next(stage.kind.wire)
            val unit = StageUnit.fromWire(
                PhonePlanUiModel.normalizedUnit(next.wire, stage.unit.wire)
            )
            StageDraft(
                next,
                unit,
                PhonePlanUiModel.convertedTarget(
                    next.wire, stage.unit.wire, unit.wire, stage.target
                )
            )
        }
    }

    fun selectStageKind(index: Int, kind: StageKind) {
        mutateStage(index) { stage ->
            val unit = StageUnit.fromWire(
                PhonePlanUiModel.normalizedUnit(kind.wire, stage.unit.wire)
            )
            StageDraft(
                kind,
                unit,
                PhonePlanUiModel.convertedTarget(
                    kind.wire, stage.unit.wire, unit.wire, stage.target
                )
            )
        }
    }

    fun toggleStageUnit(index: Int) {
        mutateStage(index) { stage ->
            if (stage.kind == StageKind.REST) return@mutateStage stage
            val to = if (stage.unit == StageUnit.DISTANCE) StageUnit.TIME else StageUnit.DISTANCE
            StageDraft(
                stage.kind,
                to,
                PhonePlanUiModel.convertedTarget(stage.kind.wire, stage.unit.wire, to.wire, stage.target)
            )
        }
    }

    fun selectStageUnit(index: Int, unit: StageUnit) {
        mutateStage(index) { stage ->
            val normalized = StageUnit.fromWire(
                PhonePlanUiModel.normalizedUnit(stage.kind.wire, unit.wire)
            )
            StageDraft(
                stage.kind,
                normalized,
                PhonePlanUiModel.convertedTarget(
                    stage.kind.wire, stage.unit.wire, normalized.wire, stage.target
                )
            )
        }
    }

    fun updateStageTarget(index: Int, target: Int) {
        mutateStage(index) { stage -> stage.copy(target = target.coerceAtLeast(1)) }
    }

    fun moveStage(index: Int, delta: Int) {
        _state.update { current ->
            val stages = current.plan.draft.stages.toMutableList()
            val target = index + delta
            if (index !in stages.indices || target !in stages.indices) return@update current
            val moved = stages.removeAt(index)
            stages.add(target, moved)
            current.copy(plan = current.plan.copy(draft = current.plan.draft.copy(stages = stages, dirty = true)))
        }
    }

    fun removeStage(index: Int) {
        _state.update { current ->
            val stages = current.plan.draft.stages.toMutableList()
            if (index !in stages.indices) return@update current
            stages.removeAt(index)
            current.copy(plan = current.plan.copy(draft = current.plan.draft.copy(stages = stages, dirty = true)))
        }
    }

    private fun mutateStage(index: Int, transform: (StageDraft) -> StageDraft) {
        _state.update { current ->
            val stages = current.plan.draft.stages.toMutableList()
            if (index !in stages.indices) return@update current
            val updated = transform(stages[index])
            if (updated == stages[index]) return@update current
            stages[index] = updated
            current.copy(plan = current.plan.copy(draft = current.plan.draft.copy(stages = stages, dirty = true)))
        }
    }

    fun applyTemplate(fartlek: Boolean) {
        val stages = ArrayList<StageDraft>()
        val requirement: String
        val name: String
        if (fartlek) {
            name = "变速跑安排"
            requirement = "快跑 2 分钟，快走恢复 1 分钟，连续完成 6 组。"
            repeat(6) {
                stages.add(StageDraft(StageKind.RUN, StageUnit.TIME, 120))
                stages.add(StageDraft(StageKind.WALK, StageUnit.TIME, 60))
            }
        } else {
            name = "距离间歇安排"
            requirement = "跑步 1 千米，随后快走恢复 200 米；按阶段顺序完成。"
            stages.add(StageDraft(StageKind.RUN, StageUnit.DISTANCE, 1000))
            stages.add(StageDraft(StageKind.WALK, StageUnit.DISTANCE, 200))
        }
        _state.update { current ->
            val draft = current.plan.draft
            current.copy(
                plan = current.plan.copy(
                    draft = draft.copy(
                        name = draft.name.ifBlank { name },
                        requirement = requirement,
                        stages = stages,
                        dirty = true
                    )
                )
            )
        }
    }

    /**
     * 放弃草稿并返回上层。
     *
     * 是否确认放弃由 UI 层根据 [PlanDraft.dirty] 决定;本方法本身不弹窗,
     * 保证状态归约保持在 ViewModel 内、呈现决策保持在 UI 内。
     */
    fun leaveEditor() {
        _state.update { current ->
            val detailId = current.plan.draft.id
            current.copy(
                plan = current.plan.copy(
                    route = if (detailId.isBlank()) PlanRoute.Library else PlanRoute.Detail(detailId),
                    draft = PlanDraft()
                )
            )
        }
    }

    /** 返回草稿是否合法;非法原因通过事件提示,不静默丢弃用户输入。 */
    fun savePlan() {
        val draft = _state.value.plan.draft
        if (draft.name.isBlank()) {
            viewModelScope.launch { _events.emit(PhoneEvent.Toast("请填写安排名称")) }
            return
        }
        if (draft.groupId.isBlank() || draft.group.isBlank()) {
            viewModelScope.launch { _events.emit(PhoneEvent.Toast("请选择所属训练计划")) }
            return
        }
        if (draft.stages.isEmpty()) {
            viewModelScope.launch { _events.emit(PhoneEvent.Toast("至少添加一项训练内容")) }
            return
        }
        ioScope.launch {
            try {
                val id = draft.id.ifBlank { UUID.randomUUID().toString() }
                val stages = JSONArray()
                draft.stages.forEach { stage ->
                    stages.put(
                        JSONObject()
                            .put("kind", stage.kind.wire)
                            .put("unit", stage.unit.wire)
                            .put("target", stage.target)
                    )
                }
                val profile = JSONObject()
                    .put("id", id)
                    .put("name", draft.name.trim())
                    .put("groupId", draft.groupId)
                    .put("group", draft.group.trim())
                    .put("requirement", draft.requirement.trim())
                    .put("stages", stages)
                PhonePlanLibrary.upsert(app, profile)
                queueLibrarySync("安排已同步到手表")
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(plan = it.plan.copy(route = PlanRoute.Detail(id), draft = it.plan.draft.copy(id = id, dirty = false)))
                    }
                    refreshPlans()
                }
                _events.emit(PhoneEvent.Toast("安排已保存"))
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("保存失败：${userError(error)}"))
            }
        }
    }

    fun deletePlan(planId: String) {
        ioScope.launch {
            try {
                PhonePlanLibrary.deletePlan(app, planId)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(plan = it.plan.copy(route = PlanRoute.Library)) }
                    refreshPlans()
                }
                _events.emit(PhoneEvent.Toast("安排已删除"))
                viewModelScope.launch(Dispatchers.IO) { queueLibrarySync("安排已删除并同步") }
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("删除安排失败 · ${userError(error)}"))
            }
        }
    }

    fun selectPlan(planId: String) {
        ioScope.launch {
            try {
                PhonePlanLibrary.select(app, planId)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(plan = it.plan.copy(route = PlanRoute.Detail(planId))) }
                    refreshPlans()
                }
                queueLibrarySync("当前安排已同步到手表")
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("设为当前失败 · ${userError(error)}"))
            }
        }
    }

    fun createGroup(name: String) {
        ioScope.launch {
            try {
                PhonePlanLibrary.createGroup(app, name)
                withContext(Dispatchers.Main) { refreshPlans() }
                queueLibrarySync("训练计划已创建")
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("创建失败 · ${userError(error)}"))
            }
        }
    }

    fun renameGroup(groupId: String, name: String) {
        ioScope.launch {
            try {
                PhonePlanLibrary.renameGroup(app, groupId, name)
                withContext(Dispatchers.Main) { refreshPlans() }
                queueLibrarySync("计划名称已更新")
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("重命名失败 · ${userError(error)}"))
            }
        }
    }

    fun deleteGroup(groupId: String) {
        ioScope.launch {
            try {
                PhonePlanLibrary.deleteGroup(app, groupId)
                withContext(Dispatchers.Main) { refreshPlans() }
                _events.emit(PhoneEvent.Toast("分组及其中安排已删除"))
                // The watch/cloud sync must NOT block the single-slot ioScope; otherwise a second
                // delete right after this one waits behind a slow transport and looks dead. Run the
                // sync on a background scope and let the next delete proceed immediately.
                viewModelScope.launch(Dispatchers.IO) { queueLibrarySync("分组及其中安排已删除") }
            } catch (error: Exception) {
                _events.emit(PhoneEvent.Toast("删除分组失败 · ${userError(error)}"))
            }
        }
    }

    /** 计划改动先落本地,再经 outbox 投影到手表;不支持传输时保留待发状态。 */
    private suspend fun queueLibrarySync(successText: String) {
        withContext(Dispatchers.Main) {
            _state.update {
                it.copy(sync = it.sync.copy(message = "计划已保存 · 正在同步", tone = Tone.Progress))
            }
        }
        val connected = connection
        val cloudConfigured = CloudSyncCredentials.readyForCloudV3(app)
        try {
            val cloudSynced = cloudConfigured && CloudSnapshotSync.syncPlans(app)
            PhoneSyncOutbox.ensureCurrentLibrary(app)
            val result = if (connected == null) {
                JSONObject().put("state", "pending")
            } else {
                PhoneSyncOutbox.drain(app, connected)
            }
            if ("synced" == result.optString("state") && cloudSynced) {
                val confirmed = connected?.let { active ->
                    try {
                        JSONObject(active.requestBlocking(
                            "GET", "/v1/plan/profile", "", 10_000L
                        ))
                    } catch (ignored: Exception) {
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            sync = it.sync.copy(
                                message = "$successText · 云端、手机、手表已一致",
                                tone = Tone.Positive
                            ),
                            plan = if (confirmed != null) planStateFrom(confirmed) else it.plan
                        )
                    }
                }
            } else {
                com.poyi.watchintervals.phone.PhonePlanProjectionWorker.schedule(app)
                val note = when {
                    !cloudConfigured ->
                        "本机已保存 · 云端未连接，其他设备不会同步"
                    !cloudSynced ->
                        "本机已保存 · 云端同步失败，已安排重试"
                    else ->
                        "云端和手机已同步 · 等待手表连接"
                }
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(sync = it.sync.copy(message = note, tone = Tone.Caution))
                    }
                }
                _events.emit(PhoneEvent.Toast(note))
            }
        } catch (error: Exception) {
            com.poyi.watchintervals.phone.PhonePlanProjectionWorker.schedule(app)
            withContext(Dispatchers.Main) {
                _state.update { it.copy(sync = it.sync.copy(message = "计划已保存 · 等待手表连接", tone = Tone.Caution)) }
            }
        }
    }

    // ---------------------------------------------------------------- 训练

    fun startLivePolling() {
        if (livePollingJob?.isActive == true) return
        livePollingJob = viewModelScope.launch {
            while (isActive) {
                pollLive()
                delay(5_000L)
            }
        }
    }

    fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
    }

    private suspend fun pollLive() {
        val connected = connection ?: return
        val snapshot = connected.snapshot()
        val ready = PhoneSyncPolicy.isTransportReady(snapshot.state)
        try {
                val payload = withContext(Dispatchers.IO) {
                    connected.requestBlocking("GET", "/v1/status", "", 8_000L)
                }
            val workout = JSONObject(payload).optJSONObject("workout")
            val live = if (workout == null) null else LiveWorkout(
                state = workout.optString("state"),
                planState = workout.optString("planState"),
                activeDurationMs = workout.optLong("activeDurationMs"),
                distanceMeters = workout.optDouble("distanceMeters", 0.0),
                currentPaceSecondsPerKm = workout.optInt("currentPaceSecondsPerKm"),
                avgPaceSecondsPerKm = workout.optLong("avgPaceSecondsPerKm"),
                heartRate = workout.optInt("heartRate"),
                heartRateZone = workout.optInt("heartRateZone"),
                averageHeartRate = workout.optInt("averageHeartRate"),
                maxHeartRate = workout.optInt("maxHeartRate"),
                cadenceSpm = workout.optInt("cadenceSpm"),
                calories = workout.optInt("calories"),
                elevationGainMeters = workout.optDouble("elevationGainMeters", 0.0),
                steps = workout.optInt("steps"),
                splitCount = workout.optInt("splitCount"),
                stageName = workout.optString("stageName"),
                stageNumber = workout.optInt("stageNumber"),
                stageCount = workout.optInt("stageCount")
            )
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(workout = WorkoutState(
                        live = live,
                        error = null,
                        actions = actionsFor(live, ready),
                        transportReady = ready
                    ))
                }
            }
        } catch (error: Exception) {
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        workout = WorkoutState(
                            live = null,
                            error = userError(error),
                            actions = if (ready) listOf(WorkoutAction.Start) else emptyList(),
                            transportReady = ready
                        )
                    )
                }
            }
        }
    }

    private fun actionsFor(live: LiveWorkout?, transportReady: Boolean): List<WorkoutAction> = when {
        live == null -> if (transportReady) listOf(WorkoutAction.Start) else emptyList()
        live.running -> listOf(WorkoutAction.Pause, WorkoutAction.Stop)
        live.paused -> listOf(WorkoutAction.Resume, WorkoutAction.Stop)
        live.preparing -> listOf(WorkoutAction.Stop)
        else -> listOf(WorkoutAction.Start)
    }

    fun control(action: WorkoutAction) {
        val connected = connection ?: return
        ioScope.launch {
            try {
                val expected = when (action) {
                    WorkoutAction.Pause -> "RUNNING"
                    WorkoutAction.Resume -> "PAUSED"
                    WorkoutAction.Start -> "STOPPED"
                    WorkoutAction.Stop -> ""
                }
                val command = JSONObject()
                    .put("commandId", UUID.randomUUID().toString())
                    .put("expiresAt", System.currentTimeMillis() + 30_000L)
                if (expected.isNotEmpty()) command.put("expectedState", expected)
                val path = when (action) {
                    WorkoutAction.Start -> "start"
                    WorkoutAction.Pause -> "pause"
                    WorkoutAction.Resume -> "resume"
                    WorkoutAction.Stop -> "stop"
                }
                connected.requestBlocking("POST", "/v1/control/$path", command.toString(), 30_000L)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(workout = it.workout.copy(notice = "操作已发送到手表")) }
                }
                pollLive()
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(workout = it.workout.copy(notice = "操作失败 · ${userError(error)}")) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 历史

    private fun historyStateFrom(array: JSONArray): HistoryState {
        val records = ArrayList<WorkoutSummary>()
        for (index in 0 until array.length()) {
            val record = array.optJSONObject(index) ?: continue
            records.add(
                WorkoutSummary(
                    id = record.optString("id"),
                    startedAt = record.optLong("startedAt"),
                    durationMs = record.optLong("durationMs"),
                    distanceMeters = record.optDouble("distanceMeters"),
                    steps = record.optInt("steps"),
                    averageHeartRate = record.optInt("averageHeartRate"),
                    routePointCount = record.optInt("routePointCount")
                )
            )
        }
        return HistoryState(
            records = records,
            summary = "${records.size} 次训练 · 点击查看地图轨迹与完整数据"
        )
    }

    fun openWorkoutDetail(recordId: String) {
        val connected = connection ?: return
        ioScope.launch {
            try {
                val detail = connected.requestBlocking(
                    "GET", "/v1/history/${android.net.Uri.encode(recordId)}", "", 20_000L
                )
                _events.emit(PhoneEvent.OpenWorkoutDetail(detail))
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(history = it.history.copy(error = "读取详情失败 · ${userError(error)} · 点击可重试"))
                    }
                }
            }
        }
    }

    fun clearHistoryError() {
        _state.update { it.copy(history = it.history.copy(error = null)) }
    }

    // ---------------------------------------------------------------- 睡眠

    fun loadSleep() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { PhoneSleepRepository.load(app) }
            _state.update { current ->
                current.copy(sleep = sleepStateFrom(cached, cached = true, note = null))
            }
            val snapshot = connection?.snapshot()
            val transportReady = snapshot != null &&
                (snapshot.primaryTransport != null || snapshot.lanAvailable)
            if (!transportReady) {
                if (cached != null) {
                    _state.update { current ->
                        current.copy(sleep = sleepStateFrom(cached, cached = true, note = "当前离线"))
                    }
                }
                return@launch
            }
            _state.update { it.copy(sleep = SleepState.Loading) }
            ioScope.launch {
                try {
                    val result = PhoneSleepSync.fetchRecent(connection, 31)
                    if ("ready" == result.optString("state")) {
                        val received = (result.optJSONArray("records")?.length() ?: 0) > 0
                        val saved = PhoneSleepRepository.mergeAndSave(app, result, System.currentTimeMillis())
                        CloudSnapshotSync.syncSleepAsync(app)
                        withContext(Dispatchers.Main) {
                            _state.update {
                                it.copy(
                                    sleep = sleepStateFrom(
                                        saved,
                                        cached = true,
                                        note = if (received) null else "手表本次没有返回新记录，保留上次数据"
                                    )
                                )
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _state.update { current ->
                                val state = result.optString("state")
                                val reason = if ("permission_required" == state) {
                                    "请在手表端打开步序并允许读取睡眠"
                                } else {
                                    "系统睡眠暂不可用：" + result.optString("error", "未知错误")
                                }
                                current.copy(sleep = sleepStateFrom(cached, cached = true, note = reason))
                            }
                        }
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        _state.update { current ->
                            current.copy(
                                sleep = sleepStateFrom(
                                    cached,
                                    cached = true,
                                    note = "刷新失败：" + userError(error)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sleepStateFrom(payload: JSONObject?, cached: Boolean, note: String?): SleepState {
        if (payload == null) {
            return SleepState.Empty(
                title = "本机还没有已同步的睡眠数据",
                detail = "连接手表并同步一次后，这里可离线查看最近 31 天。"
            )
        }
        val records = payload.optJSONArray("records")
        if (records == null || records.length() == 0) {
            return SleepState.Empty(
                title = "最近没有睡眠记录",
                detail = note ?: "系统已返回空列表；没有用估算数据补齐。"
            )
        }
        val nights = ArrayList<SleepNightUi>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            nights.add(
                SleepNightUi(
                    overview = PhoneSleepOverview.from(record),
                    timeline = PhoneSleepTimeline.from(record)
                )
            )
        }
        val week = PhoneSleepWeek.from(records)
        val stamp = payload.optLong("cachedAt")
        val base = if (stamp > 0L) {
            "本机数据 · " + SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(stamp)) + " 同步"
        } else {
            "本机已保存的数据"
        }
        val summary = base + " · " + records.length() + " 晚"
        return SleepState.Ready(
            nights = nights,
            week = week,
            cached = cached,
            summary = if (note.isNullOrEmpty()) summary else "$summary · $note",
            note = note
        )
    }

    // ---------------------------------------------------------------- 设置与云端

    fun toggleSetup(show: Boolean) {
        _state.update { it.copy(setup = it.setup.copy(visible = show)) }
    }

    fun updateLanHost(value: String) {
        _state.update { it.copy(setup = it.setup.copy(lanHost = value)) }
    }

    fun updatePairingCode(value: String) {
        _state.update { it.copy(setup = it.setup.copy(pairingCode = value)) }
    }

    fun updateCloudEndpoint(value: String) {
        _state.update { it.copy(setup = it.setup.copy(cloud = it.setup.cloud.copy(endpoint = value))) }
    }

    fun saveCloud(token: String) {
        ioScope.launch {
            val endpoint = _state.value.setup.cloud.endpoint
            // 未输入新 token 时保留已保存凭据,不把 Keystore 包装的 token 读回 UI 层。
            val saved = if (token.isBlank()) {
                CloudSyncCredentials.saveEndpointKeepingToken(app, endpoint)
            } else {
                CloudSyncCredentials.save(app, endpoint, token)
            }
            if (!saved) {
                _events.emit(PhoneEvent.Toast("请输入 HTTPS ${PhoneCloudSetupSpec.ENDPOINT_HINT} 地址和有效设备 token"))
                return@launch
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        setup = it.setup.copy(
                            cloud = it.setup.cloud.copy(
                                configured = true,
                                tokenSaved = true,
                                statusLabel = "已配置 Cloud V3",
                                tone = Tone.Positive
                            )
                        )
                    )
                }
            }
            if (CloudSyncCredentials.readyForCloudV3(app)) {
                CloudSnapshotSync.syncAsync(app)
                _events.emit(PhoneEvent.Toast("云同步配置已保存，正在后台测试"))
            } else {
                _events.emit(PhoneEvent.Toast("设备 token 保存失败"))
            }
        }
    }

    fun provisionCloudFromIntent(endpoint: String?, key: String?) {
        if (endpoint == null || key == null) return
        ioScope.launch {
            if (CloudSyncCredentials.save(app, endpoint, key)) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            setup = it.setup.copy(
                                cloud = it.setup.cloud.copy(
                                    endpoint = endpoint,
                                    configured = true,
                                    tokenSaved = true,
                                    statusLabel = "已配置 Cloud V3",
                                    tone = Tone.Positive
                                )
                            )
                        )
                    }
                }
                if (CloudSyncCredentials.readyForCloudV3(app)) CloudSnapshotSync.syncAsync(app)
            }
        }
    }

    // ---------------------------------------------------------------- 局域网发现

    fun reconnectWatch() {
        val manager = connection ?: return
        val pairing = _state.value.setup.pairingCode.trim()
        val host = _state.value.setup.lanHost.trim()
        ioScope.launch {
            manager.configurePairing(pairing)
            manager.configureLan(host, pairing)
            manager.connectNow()
            withContext(Dispatchers.Main) { discoverWatch() }
        }
    }

    fun discoverWatch() {
        stopDiscovery()
        val wifi = app.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("watchintervals-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        nsdManager = app.getSystemService(android.content.Context.NSD_SERVICE) as? NsdManager
        val manager = nsdManager ?: return
        resolving = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) = stopDiscovery()
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) = releaseMulticast()
            override fun onDiscoveryStopped(type: String) = releaseMulticast()
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_watchintervals._tcp") || resolving) return
                resolving = true
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                    }

                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val address = service.host?.hostAddress ?: ""
                        val connected = connection ?: run { resolving = false; return }
                        var credential = connected.identity().lanCredential()
                        if (credential.isEmpty()) credential = _state.value.setup.pairingCode
                        val pairing = credential
                        if (address.isEmpty()) {
                            resolving = false
                            return
                        }
                        // 六位规则只约束用户首次输入的配对码;已配对的手机持有长期 LAN 凭据,长度不为 6。
                        if (!connected.identity().isPaired() && pairing.trim().length != 6) {
                            resolving = false
                            viewModelScope.launch {
                                _state.update { it.copy(setup = it.setup.copy(lanHost = address)) }
                                _events.emit(PhoneEvent.Toast("已发现手表，请输入配对码"))
                            }
                            stopDiscovery()
                            return
                        }
                        ioScope.launch {
                            try {
                                val status = JSONObject(WatchClient(address, pairing.trim()).get("/v1/status"))
                                val discoveredId = status.optString("deviceId")
                                val expectedId = app.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE)
                                    .getString("watch_device_id", "") ?: ""
                                if (expectedId.isNotEmpty() && expectedId != discoveredId) {
                                    resolving = false
                                    return@launch
                                }
                                withContext(Dispatchers.Main) {
                                    _state.update { it.copy(setup = it.setup.copy(lanHost = address)) }
                                }
                                app.getSharedPreferences("connection", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("watch_device_id", discoveredId).apply()
                                _events.emit(PhoneEvent.Toast("已发现 ${status.optString("device")} · LAN 加速可用"))
                                stopDiscovery()
                                sync()
                            } catch (ignored: Exception) {
                                resolving = false
                            }
                        }
                    }
                })
            }
        }
        discoveryListener = listener
        try {
            manager.discoverServices("_watchintervals._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            stopDiscovery()
        }
    }

    private fun stopDiscovery() {
        val manager = nsdManager
        val listener = discoveryListener
        if (manager != null && listener != null) {
            try {
                manager.stopServiceDiscovery(listener)
            } catch (ignored: Exception) {
                releaseMulticast()
            }
        } else {
            releaseMulticast()
        }
        discoveryListener = null
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    // ---------------------------------------------------------------- 工具

    private fun findPlan(library: JSONObject, id: String): JSONObject? {
        val plans = library.optJSONArray("plans") ?: return null
        for (index in 0 until plans.length()) {
            val item = plans.optJSONObject(index) ?: continue
            if (id == item.optString("id")) return item
        }
        return null
    }

    private fun lastSyncLabel(): String {
        val value = app.getSharedPreferences("phone_sync_ui", android.content.Context.MODE_PRIVATE)
            .getLong("last_full_sync_at", 0L)
        return if (value <= 0L) {
            "已同步的数据会保存在本机"
        } else {
            "上次完整同步 · " + SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(value))
        }
    }

    private fun rememberLastSync(value: Long) {
        app.getSharedPreferences("phone_sync_ui", android.content.Context.MODE_PRIVATE)
            .edit().putLong("last_full_sync_at", value.coerceAtLeast(0L)).apply()
    }

    private fun connectionLabel(state: ConnectionState): String = when (state) {
        ConnectionState.CONNECTED_BLE_LAN -> "蓝牙连接 · LAN 加速"
        ConnectionState.CONNECTED_BLE -> "蓝牙已连接"
        ConnectionState.CONNECTED_LAN -> "LAN 已连接 · 正在恢复蓝牙"
        ConnectionState.SCANNING -> "正在通过蓝牙寻找手表"
        ConnectionState.CONNECTING_BLE, ConnectionState.DISCOVERING_SERVICES,
        ConnectionState.SUBSCRIBING, ConnectionState.AUTHENTICATING -> "正在建立蓝牙连接"
        ConnectionState.BLUETOOTH_DISABLED -> "蓝牙已关闭"
        ConnectionState.UNPAIRED -> "请输入手表上的配对码"
        ConnectionState.BACKOFF -> "手表不在附近，稍后自动重连"
        else -> "尚未连接"
    }

    private fun connectionUi(
        snapshot: WatchConnectionManager.Snapshot,
        paired: Boolean,
        pairingCode: String
    ): ConnectionUi = ConnectionUi(
        state = snapshot.state,
        label = connectionLabel(snapshot.state),
        tone = connectionTone(snapshot.state),
        paired = paired,
        transportReady = PhoneSyncPolicy.isTransportReady(snapshot.state),
        pairingCode = if (paired) "" else pairingCode,
        primaryTransport = snapshot.primaryTransport?.name,
        bulkTransport = snapshot.bulkTransport?.name,
        lastSeenAt = snapshot.lastSeenAt,
        lastSuccessfulRequestAt = snapshot.lastSuccessfulRequestAt,
        rssi = snapshot.rssi,
        mtu = snapshot.mtu,
        pendingOperations = snapshot.pendingOperations,
        notificationsSubscribed = snapshot.notificationsSubscribed,
        lanAvailable = snapshot.lanAvailable,
        lastDisconnectReason = snapshot.lastDisconnectReason
    )

    private fun connectionTone(state: ConnectionState): Tone = when (state) {
        ConnectionState.CONNECTED_BLE_LAN, ConnectionState.CONNECTED_BLE -> Tone.Positive
        ConnectionState.CONNECTED_LAN -> Tone.Progress
        ConnectionState.BLUETOOTH_DISABLED, ConnectionState.UNPAIRED -> Tone.Negative
        ConnectionState.SCANNING, ConnectionState.CONNECTING_BLE,
        ConnectionState.DISCOVERING_SERVICES, ConnectionState.SUBSCRIBING,
        ConnectionState.AUTHENTICATING, ConnectionState.BACKOFF -> Tone.Caution
        else -> Tone.Neutral
    }

    private fun userError(error: Throwable): String {
        var cause: Throwable? = error
        while (cause?.cause != null) cause = cause.cause
        val message = cause?.message ?: ""
        val lower = message.lowercase(Locale.ROOT)
        if (lower.contains("timeout")) return "请求超时"
        if (lower.contains("pair") || lower.contains("401")) return "手表配对需要重新确认"
        if (lower.contains("bluetooth") || lower.contains("ble") || lower.contains("gatt") ||
            lower.contains("offline")
        ) return "手表连接中断"
        if (message.any { it in '\u4e00'..'\u9fa5' } && message.length <= 80) return message
        return "暂时无法完成，请稍后重试"
    }

    /** 供 UI 直接复用的展示格式化,与 PhoneFormat 保持一致。 */
    fun formatDuration(millis: Long): String = PhoneFormat.duration(millis)
    fun formatDistance(meters: Double): String = PhoneFormat.distance(meters)
    fun formatPace(millis: Long, meters: Double): String = PhoneFormat.pace(millis, meters)
    fun formatMinutes(minutes: Int): String = PhoneFormat.minutesHuman(minutes)
}
