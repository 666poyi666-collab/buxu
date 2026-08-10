package com.poyi.watchintervals.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;
import com.poyi.watchintervals.phone.connection.ConnectionState;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION_RELAY = 44;
    private static final int REQUEST_BLUETOOTH = 45;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> stages = new ArrayList<>();
    private EditText host, code, cloudEndpoint, cloudKey, planName, planGroup, planRequirement;
    private TextView connection, syncSummary, historySummary, sleepSummary, currentWatchPlan,
            currentPlanSync, planDetailName, planDetailMeta, planDetailRequirement,
            planDetailSequence, planEditorTitle;
    private Button syncAction;
    private LinearLayout planList, historyList, sleepList, savedPlanList, planCard,
            controlCard, historyCard, sleepCard, planLibraryPanel, planDetailPanel,
            planDetailStages, planEditorPanel;
    private String editingPlanId = "";
    private String detailPlanId = "";
    private String watchCurrentPlanId = "";
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private boolean resolving;
    private boolean foreground;
    private WatchConnectionManager watchConnection;
    private final java.util.concurrent.atomic.AtomicBoolean fullSyncInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private ConnectionState previousConnectionState;
    private boolean suppressPlanDraftTracking;
    private boolean planDraftDirty;
    private View statusDot;
    private TextView setupChevron;
    private LinearLayout setupPanel;
    private ScrollView setupScroll;
    private ScrollView planScroll, controlScroll, historyScroll, sleepScroll;
    private PhoneTabView[] navItems;
    private int currentSection;
    private int bottomSystemInset;
    private int navigationHeight;
    private TextView liveState, liveTime, liveMeta;
    private ActivityRing liveRing;
    private LinearLayout liveActions;
    private String liveActionsState = "";
    private volatile boolean livePollInFlight;
    private final android.os.Handler liveHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler syncRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable syncRetry = () -> {
        if(foreground&&watchConnection!=null&&PhoneSyncPolicy.isTransportReady(
                watchConnection.snapshot().state)&&!fullSyncInFlight.get())syncAll();
    };
    private final Runnable livePoller = new Runnable() { @Override public void run() {
        pollLiveStatus();
        liveHandler.postDelayed(this, 5_000L);
    }};
    private final WatchConnectionManager.Observer connectionObserver=snapshot->{
        connection.setText(connectionLabel(snapshot));
        updateStatusDot(snapshot);
        if(watchConnection!=null&&watchConnection.identity().isPaired()&&code!=null){
            code.setText("");code.setHint("已完成安全配对");
        }
        boolean shouldSync=PhoneSyncPolicy.shouldAutoSync(previousConnectionState,
                snapshot.state,fullSyncInFlight.get());
        previousConnectionState=snapshot.state;
        if(shouldSync)syncAll();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startForegroundService(new Intent(this, PhonePlanBridgeService.class));
        buildUi();
        watchConnection=WatchConnectionManager.get(this);
        watchConnection.observe(connectionObserver);
        android.content.SharedPreferences preferences = getSharedPreferences("connection", MODE_PRIVATE);
        host.setText(preferences.getString("host", ""));
        if(watchConnection.identity().isPaired()){code.setText("");code.setHint("已完成安全配对");}else code.setText(watchConnection.identity().pairingCode());
        CloudSyncCredentials.Config cloud = CloudSyncCredentials.load(this);
        cloudEndpoint.setText(cloud.endpoint);
        cloudKey.setText("");
        if (cloud.configured()) cloudKey.setHint("已安全保存设备 token");
        provisionCloudFromIntent(getIntent());
        watchConnection.configurePairing(code.getText().toString().trim());
        watchConnection.configureLan(host.getText().toString().trim(),code.getText().toString().trim());
        toggleSetup(!watchConnection.identity().isPaired());
        ensureBluetoothConnection();
        discoverWatch();
        restoreUiState(state);
    }

    @Override protected void onResume() {
        super.onResume(); foreground = true;
        if (currentSection == 1) { liveHandler.removeCallbacks(livePoller); liveHandler.post(livePoller); }
    }
    @Override protected void onPause() { foreground = false; liveHandler.removeCallbacks(livePoller); super.onPause(); }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Palette.BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setNavigationBarDividerColor(Palette.BG);
        int topInset=dp(10);
        int bottomInset=0;
        bottomSystemInset=bottomInset;
        float fontScale=getResources().getConfiguration().fontScale;
        navigationHeight=dp(Math.round(66f+Math.max(0f,fontScale-1f)*20f));
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Palette.BG);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(Palette.BG);
        root.addView(shell,new FrameLayout.LayoutParams(-1,-1));

        // Fixed header: product title plus a one-line connection status. The old full-height
        // "连接手表" card topped every tab forever; for a paired phone, connection is status, not
        // a task, so setup collapses behind a tap on the status row.
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(20),topInset,dp(20),dp(4));
        LinearLayout brandRow=new LinearLayout(this);brandRow.setGravity(Gravity.CENTER_VERTICAL);
        PhoneSymbolView brandMark=new PhoneSymbolView(this,PhoneSymbol.BRAND);
        brandMark.setTint(Palette.MOVE);brandMark.setEmphasized(true);
        brandRow.addView(brandMark,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView productTitle=text("步序",18,true,Palette.TEXT);
        productTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        productTitle.setLetterSpacing(.08f);
        LinearLayout.LayoutParams productTitleParams=new LinearLayout.LayoutParams(-2,dp(36));
        productTitleParams.leftMargin=dp(8);brandRow.addView(productTitle,productTitleParams);
        header.addView(brandRow);
        LinearLayout statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot=new View(this);statusDot.setBackground(rounded(Color.GRAY,10));
        LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(dp(10),dp(10));dotParams.rightMargin=dp(8);statusRow.addView(statusDot,dotParams);
        LinearLayout statusCopy=section();statusCopy.setClickable(true);statusCopy.setFocusable(true);
        connection=text("尚未连接",14,true,Palette.TEXT);connection.setPadding(0,0,0,0);
        syncSummary=text(lastSyncLabel(),12,false,Palette.TEXT_DIM);syncSummary.setPadding(0,0,0,0);
        statusCopy.addView(connection);statusCopy.addView(syncSummary);
        statusCopy.setOnClickListener(v->toggleSetup(setupScroll==null||setupScroll.getVisibility()!=View.VISIBLE));
        statusRow.addView(statusCopy,new LinearLayout.LayoutParams(0,-2,1));
        syncAction=button("同步",Palette.CARD_HIGH,Palette.TEXT);syncAction.setTextSize(13);
        syncAction.setContentDescription("立即同步手表数据");
        LinearLayout.LayoutParams syncParams=new LinearLayout.LayoutParams(dp(64),dp(40));
        syncParams.rightMargin=dp(4);statusRow.addView(syncAction,syncParams);
        setupChevron=text("设置",13,true,Palette.TEXT_DIM);setupChevron.setGravity(Gravity.CENTER);
        setupChevron.setClickable(true);setupChevron.setFocusable(true);
        setupChevron.setContentDescription("打开连接与云同步设置");
        setupChevron.setOnClickListener(v->toggleSetup(setupScroll==null||setupScroll.getVisibility()!=View.VISIBLE));
        statusRow.addView(setupChevron,new LinearLayout.LayoutParams(dp(48),dp(40)));
        header.addView(statusRow,new LinearLayout.LayoutParams(-1,dp(60)));

        setupPanel=compactCard();
        setupPanel.setBackground(glassSurface(24));
        setupPanel.setElevation(dp(8));
        host=input("LAN 诊断地址");host.setVisibility(View.GONE);
        code=input("手表上的 6 位配对码");code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        setupPanel.addView(host);setupPanel.addView(code);
        LinearLayout connectActions=new LinearLayout(this);
        Button discover=button("连接手表",Palette.CARD_HIGH,Palette.TEXT);
        Button connect=button("立即同步",Palette.EXERCISE,Palette.INK);
        connectActions.addView(discover,weight());connectActions.addView(connect,weight());setupPanel.addView(connectActions);
        setupPanel.addView(text(PhoneCloudSetupSpec.TITLE,14,true,Palette.TEXT));
        cloudEndpoint=input(PhoneCloudSetupSpec.ENDPOINT_HINT);
        cloudEndpoint.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        cloudKey=input(PhoneCloudSetupSpec.TOKEN_HINT);
        cloudKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        setupPanel.addView(cloudEndpoint);setupPanel.addView(cloudKey);
        Button saveCloud=button(PhoneCloudSetupSpec.SAVE_ACTION,Palette.CARD_HIGH,Palette.TEXT);
        setupPanel.addView(saveCloud);
        setupPanel.addView(text(PhoneCloudSetupSpec.SECURITY_NOTE,12,false,Palette.TEXT_DIM));
        shell.addView(header);

        planCard = section();
        planCard.addView(pageTitle("训练计划", "把每一天的训练，排成看得懂的节奏"));
        planLibraryPanel=new LinearLayout(this);planLibraryPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout currentPlanCard=compactCard();currentPlanCard.setPadding(dp(16),dp(13),dp(16),dp(13));
        currentPlanCard.setBackground(roundedStroke(Palette.FILL_SELECTED,20,Palette.EXERCISE,1));
        currentPlanCard.addView(text("手表当前安排",12,true,Palette.TEXT_DIM));
        currentWatchPlan=text("连接后读取",18,true,Palette.TEXT);currentPlanCard.addView(currentWatchPlan);
        currentPlanSync=text("手机修改会自动排队同步",12,false,Palette.TEXT_DIM);currentPlanCard.addView(currentPlanSync);
        LinearLayout.LayoutParams currentPlanParams=margin();currentPlanParams.topMargin=dp(14);planLibraryPanel.addView(currentPlanCard,currentPlanParams);
        LinearLayout libraryHeader = new LinearLayout(this); libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        libraryHeader.addView(text("我的训练计划",20,true,Palette.TEXT),new LinearLayout.LayoutParams(0,dp(64),1));
        Button createGroup=button("新建",Palette.MOVE,Palette.INK);createGroup.setContentDescription("新建训练计划分组");
        libraryHeader.addView(createGroup,new LinearLayout.LayoutParams(dp(76),dp(48)));
        planLibraryPanel.addView(libraryHeader);
        savedPlanList=new LinearLayout(this); savedPlanList.setOrientation(LinearLayout.VERTICAL); planLibraryPanel.addView(savedPlanList);
        planCard.addView(planLibraryPanel);

        planDetailPanel=section();planDetailPanel.setVisibility(View.GONE);
        LinearLayout detailHeader=new LinearLayout(this);detailHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button closeDetail=button("返回计划",Color.TRANSPARENT,Palette.MOVE);
        closeDetail.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        closeDetail.setContentDescription("返回训练计划列表");
        detailHeader.addView(closeDetail,new LinearLayout.LayoutParams(0,dp(48),1));
        Button editDetail=button("编辑",Palette.CARD_HIGH,Palette.TEXT);
        detailHeader.addView(editDetail,new LinearLayout.LayoutParams(dp(72),dp(44)));
        planDetailPanel.addView(detailHeader);
        planDetailName=text("安排详情",28,true,Palette.TEXT);planDetailPanel.addView(planDetailName);
        planDetailMeta=text("",14,false,Palette.TEXT_DIM);planDetailPanel.addView(planDetailMeta);
        LinearLayout detailOverview=card();detailOverview.setPadding(dp(16),dp(14),dp(16),dp(14));
        detailOverview.addView(text("训练节奏",12,true,Palette.TEXT_DIM));
        planDetailSequence=text("",18,true,Palette.TEXT);planDetailSequence.setLineSpacing(dp(4),1f);
        detailOverview.addView(planDetailSequence);
        planDetailRequirement=text("",13,false,Palette.TEXT_DIM);detailOverview.addView(planDetailRequirement);
        LinearLayout.LayoutParams detailOverviewParams=margin();detailOverviewParams.topMargin=dp(14);
        planDetailPanel.addView(detailOverview,detailOverviewParams);
        planDetailPanel.addView(text("阶段明细",17,true,Palette.TEXT));
        planDetailStages=section();planDetailPanel.addView(planDetailStages);
        Button useDetail=button("设为手表当前安排",Palette.EXERCISE,Palette.INK);
        useDetail.setContentDescription("设为当前安排并同步到手表");
        LinearLayout.LayoutParams useDetailParams=margin();useDetailParams.topMargin=dp(16);
        planDetailPanel.addView(useDetail,useDetailParams);
        Button deleteDetail=button("删除这个安排",Palette.FILL_DANGER,Palette.RED);
        LinearLayout.LayoutParams deleteDetailParams=margin();deleteDetailParams.topMargin=dp(8);
        planDetailPanel.addView(deleteDetail,deleteDetailParams);
        planCard.addView(planDetailPanel);

        planEditorPanel=new LinearLayout(this);planEditorPanel.setOrientation(LinearLayout.VERTICAL);planEditorPanel.setVisibility(View.GONE);
        LinearLayout editorHeader=new LinearLayout(this);editorHeader.setGravity(Gravity.CENTER_VERTICAL);
        Button closeEditor=button("返回详情",Color.TRANSPARENT,Palette.MOVE);closeEditor.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        closeEditor.setContentDescription("返回安排详情");editorHeader.addView(closeEditor,new LinearLayout.LayoutParams(0,dp(48),1));
        Button saveTop=button("保存",Palette.EXERCISE,Palette.INK);editorHeader.addView(saveTop,new LinearLayout.LayoutParams(dp(72),dp(48)));
        planEditorPanel.addView(editorHeader);
        planEditorTitle=text("编辑安排",28,true,Palette.TEXT);planEditorPanel.addView(planEditorTitle);
        planEditorPanel.addView(text("安排信息",16,true,Palette.TEXT));
        planName = input("安排名称，例如：第1天"); planGroup = input("所属训练计划，例如：减肥计划");
        planRequirement = input("今天的训练说明（可选）");
        planRequirement.setSingleLine(false); planRequirement.setMinLines(2); planRequirement.setGravity(Gravity.TOP);LinearLayout.LayoutParams requirementParams=new LinearLayout.LayoutParams(-1,dp(92));requirementParams.topMargin=dp(7);planRequirement.setLayoutParams(requirementParams);
        planEditorPanel.addView(planName); planEditorPanel.addView(planGroup); planEditorPanel.addView(planRequirement);
        planEditorPanel.addView(text("快速填充训练内容",16,true,Palette.TEXT));
        LinearLayout templates = new LinearLayout(this);
        Button intervalPlan = button("1千米 + 200米", Palette.CARD_HIGH, Palette.TEXT);
        Button fartlekPlan = button("法特莱克跑", Palette.CARD_HIGH, Palette.TEXT);
        templates.addView(intervalPlan, weight()); templates.addView(fartlekPlan, weight()); planEditorPanel.addView(templates);
        planEditorPanel.addView(text("训练内容",18,true,Palette.TEXT));
        planEditorPanel.addView(text("每项都可选择按时间或距离，支持交替组合",13,false,Palette.TEXT_DIM));
        planList = new LinearLayout(this); planList.setOrientation(LinearLayout.VERTICAL); planEditorPanel.addView(planList);
        LinearLayout additions = new LinearLayout(this);
        Button addRun = button("+ 跑步", Palette.FILL_RUN, Palette.EXERCISE);
        Button addWalk = button("+ 快走", Palette.FILL_WALK, Palette.STAND);
        Button addRest = button("+ 休息", Palette.FILL_REST, Palette.YELLOW);
        additions.addView(addRun, weight()); additions.addView(addWalk, weight()); additions.addView(addRest, weight()); planEditorPanel.addView(additions);
        Button save=button("保存安排",Palette.EXERCISE,Palette.INK);
        LinearLayout.LayoutParams saveParams=margin();saveParams.topMargin=dp(14);planEditorPanel.addView(save,saveParams);
        planEditorPanel.addView(text("保存后自动同步；只有“设为当前”才会改变手表正在使用的安排。",12,false,Palette.TEXT_DIM));
        planCard.addView(planEditorPanel);

        // Live remote: the watch's /v1/status workout block drives the readout and which actions
        // even exist. Four state-blind buttons were a prototype leftover — pressing 开始 mid-run
        // or 继续 while idle only produced STATE_MISMATCH errors.
        controlCard = section();
        controlCard.addView(pageTitle("训练", "手表实时数据与训练控制"));
        // Fitness-style hero: the active clock sits inside a gradient progress ring that fills
        // as plan stages complete.
        FrameLayout ringBox=new FrameLayout(this);
        liveRing=new ActivityRing(this);
        ringBox.addView(liveRing,new FrameLayout.LayoutParams(dp(176),dp(176),Gravity.CENTER));
        LinearLayout ringCenter=new LinearLayout(this);ringCenter.setOrientation(LinearLayout.VERTICAL);ringCenter.setGravity(Gravity.CENTER);
        liveTime=text("--:--",28,true,Palette.TEXT);liveTime.setFontFeatureSettings("tnum");liveTime.setGravity(Gravity.CENTER);
        liveState=text("未在训练",13,true,Palette.TEXT_DIM);liveState.setGravity(Gravity.CENTER);liveState.setMaxLines(2);
        ringCenter.addView(liveTime);ringCenter.addView(liveState);
        ringBox.addView(ringCenter,new FrameLayout.LayoutParams(dp(150),-2,Gravity.CENTER));
        ringBox.setBackground(roundedStroke(Palette.CARD,24,Palette.BORDER,1));
        ringBox.setElevation(dp(2));
        LinearLayout.LayoutParams ringBoxParams=new LinearLayout.LayoutParams(-1,dp(220));ringBoxParams.topMargin=dp(16);
        controlCard.addView(ringBox,ringBoxParams);
        liveMeta=text("连接手表后显示实时数据",14,false,Palette.TEXT_DIM);liveMeta.setGravity(Gravity.CENTER_HORIZONTAL);controlCard.addView(liveMeta);
        liveActions=new LinearLayout(this);controlCard.addView(liveActions);
        rebuildLiveActions("idle");
        TextView controlHint=text("操作即时发送到手表 · 训练状态每 5 秒刷新",12,false,Palette.TEXT_DIM);controlHint.setGravity(Gravity.CENTER_HORIZONTAL);controlCard.addView(controlHint);

        historyCard = section(); historyCard.addView(pageTitle("训练历史", "每次出发，都留下可复盘的数据"));
        historySummary = text("连接后读取", 14, false, Palette.TEXT_DIM); historyCard.addView(historySummary);
        historyList = new LinearLayout(this); historyList.setOrientation(LinearLayout.VERTICAL); historyCard.addView(historyList);
        sleepCard = section(); sleepCard.addView(pageTitle("睡眠", "恢复也是训练的一部分"));
        sleepSummary = text("已同步的数据会保存在本机，离线也能查看", 14, false, Palette.TEXT_DIM); sleepCard.addView(sleepSummary);
        sleepList = new LinearLayout(this); sleepList.setOrientation(LinearLayout.VERTICAL); sleepCard.addView(sleepList);

        FrameLayout content=new FrameLayout(this);
        planScroll=wrapContent(planCard);controlScroll=wrapContent(controlCard);historyScroll=wrapContent(historyCard);sleepScroll=wrapContent(sleepCard);
        content.addView(planScroll);content.addView(controlScroll);content.addView(historyScroll);content.addView(sleepScroll);
        setupScroll=new ScrollView(this);setupScroll.setVerticalScrollBarEnabled(false);setupScroll.setFillViewport(true);setupScroll.setBackgroundColor(Palette.BG);setupScroll.setVisibility(View.GONE);
        LinearLayout setupBody=section();setupBody.setPadding(dp(20),dp(8),dp(20),navigationHeight+dp(38)+bottomSystemInset);setupBody.addView(setupPanel,new LinearLayout.LayoutParams(-1,-2));setupScroll.addView(setupBody,new FrameLayout.LayoutParams(-1,-2));content.addView(setupScroll);
        shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        // Real bottom navigation: destinations stay put while content scrolls beneath them. The
        // old tab chips lived inside the scroll and drifted away with the content.
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(5),dp(5),dp(5),dp(5));
        nav.setBackground(glassSurface(28));nav.setElevation(dp(18));nav.setClipToOutline(true);
        navItems=new PhoneTabView[PhoneNavigationSpec.ITEMS.length];
        for(int i=0;i<navItems.length;i++){
            final int destination=i;
            navItems[i]=new PhoneTabView(this,PhoneNavigationSpec.ITEMS[i]);
            navItems[i].setOnClickListener(v->{toggleSetup(false);showSection(destination);if(destination==3)loadSleep();});
            nav.addView(navItems[i],new LinearLayout.LayoutParams(0,-1,1));
        }
        FrameLayout.LayoutParams navParams=new FrameLayout.LayoutParams(-1,navigationHeight,Gravity.BOTTOM);
        navParams.setMargins(dp(14),0,dp(14),dp(8)+bottomInset);root.addView(nav,navParams);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int top,bottom;
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets system=insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top=system.top;bottom=system.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();
            }
            header.setPadding(dp(20),top+dp(10),dp(20),dp(4));
            bottomSystemInset=bottom;
            FrameLayout.LayoutParams applied=(FrameLayout.LayoutParams)nav.getLayoutParams();
            applied.bottomMargin=dp(8)+bottom;nav.setLayoutParams(applied);
            updateContentInsets();
            return insets;
        });
        root.requestApplyInsets();

        connect.setOnClickListener(v -> syncAll());
        syncAction.setOnClickListener(v -> syncAll());
        saveCloud.setOnClickListener(v -> saveCloudConfig());
        discover.setOnClickListener(v -> discoverWatch());
        addRun.setOnClickListener(v -> addStage("RUN", "DISTANCE", 1000));
        addWalk.setOnClickListener(v -> addStage("WALK", "DISTANCE", 200));
        addRest.setOnClickListener(v -> addStage("REST", "TIME", 60));
        save.setOnClickListener(v -> saveEditedPlan());
        saveTop.setOnClickListener(v -> saveEditedPlan());
        createGroup.setOnClickListener(v -> showGroupNameDialog(null, ""));
        closeDetail.setOnClickListener(v->showPlanLibrary());
        editDetail.setOnClickListener(v->editDetailPlan());
        useDetail.setOnClickListener(v->selectPlan(detailPlanId));
        deleteDetail.setOnClickListener(v->confirmDeletePlan(detailPlanId));
        closeEditor.setOnClickListener(v->leavePlanEditor());
        intervalPlan.setOnClickListener(v -> applyTemplate(false));
        fartlekPlan.setOnClickListener(v -> applyTemplate(true));
        android.text.TextWatcher draftWatcher=new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){}
            public void afterTextChanged(android.text.Editable value){markPlanDraftDirty();}
        };
        planName.addTextChangedListener(draftWatcher);planGroup.addTextChangedListener(draftWatcher);
        planRequirement.addTextChangedListener(draftWatcher);
        showSection(0);
        renderSavedPlans();
    }

    private ScrollView wrapContent(LinearLayout cardBody){
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),navigationHeight+dp(38)+bottomSystemInset);
        body.addView(cardBody,new LinearLayout.LayoutParams(-1,-2));
        scroll.addView(body,new FrameLayout.LayoutParams(-1,-2));
        return scroll;
    }

    private void updateContentInsets(){
        ScrollView[] pages={planScroll,controlScroll,historyScroll,sleepScroll,setupScroll};
        for(ScrollView page:pages)if(page!=null&&page.getChildCount()>0){
            View body=page.getChildAt(0);
            body.setPadding(dp(20),dp(8),dp(20),navigationHeight+dp(38)+bottomSystemInset);
        }
    }

    private void showSection(int section){
        currentSection=section;
        planScroll.setVisibility(section==0?View.VISIBLE:View.GONE);
        controlScroll.setVisibility(section==1?View.VISIBLE:View.GONE);
        historyScroll.setVisibility(section==2?View.VISIBLE:View.GONE);
        sleepScroll.setVisibility(section==3?View.VISIBLE:View.GONE);
        // Returning to the Plan tab resumes an open detail/editor instead of silently dropping it.
        if(section==0&&planLibraryPanel.getVisibility()!=View.VISIBLE
                &&planDetailPanel.getVisibility()!=View.VISIBLE
                &&planEditorPanel.getVisibility()!=View.VISIBLE)showPlanLibrary();
        for(int i=0;i<navItems.length;i++)navItems[i].setActive(i==section);
        liveHandler.removeCallbacks(livePoller);
        if(section==1)liveHandler.post(livePoller);
    }

    private void updateStatusDot(WatchConnectionManager.Snapshot snapshot){
        if(statusDot==null)return;
        int color;
        switch(snapshot.state){
            case CONNECTED_BLE_LAN:case CONNECTED_BLE:color=Palette.GREEN;break;
            case CONNECTED_LAN:color=Palette.STAND;break;
            case BLUETOOTH_DISABLED:case UNPAIRED:color=Palette.RED;break;
            case SCANNING:case CONNECTING_BLE:case DISCOVERING_SERVICES:case SUBSCRIBING:case AUTHENTICATING:case BACKOFF:color=Palette.ORANGE;break;
            default:color=Palette.TEXT_DIM;
        }
        statusDot.setBackground(rounded(color,10));
    }

    private void toggleSetup(boolean show){
        if(setupScroll==null)return;
        setupScroll.setVisibility(show?View.VISIBLE:View.GONE);
        if(setupChevron!=null)setupChevron.setText(show?"收起":"设置");
    }

    private void rebuildLiveActions(String state){
        if(liveActions==null||state.equals(liveActionsState))return;
        liveActionsState=state;
        liveActions.removeAllViews();
        if("RUNNING".equals(state)){addLiveAction("暂停","pause",Palette.CARD_HIGH,Palette.TEXT);addLiveAction("结束","stop",Palette.FILL_DANGER,Palette.RED);}
        else if("PAUSED".equals(state)){addLiveAction("继续","resume",Palette.EXERCISE,Palette.INK);addLiveAction("结束","stop",Palette.FILL_DANGER,Palette.RED);}
        else if("PREPARING".equals(state)){addLiveAction("结束准备","stop",Palette.CARD_HIGH,Palette.TEXT);}
        else if("unavailable".equals(state)){
            Button connect=button("打开连接设置",Palette.CARD_HIGH,Palette.TEXT);
            connect.setOnClickListener(v->toggleSetup(true));
            liveActions.addView(connect,weight());
        }
        else {addLiveAction("开始训练","start",Palette.EXERCISE,Palette.INK);}
    }

    private void addLiveAction(String label,String action,int bg,int fg){
        Button item=button(label,bg,fg);item.setOnClickListener(v->control(action));liveActions.addView(item,weight());
    }

    private void pollLiveStatus(){
        if(watchConnection==null||livePollInFlight)return;
        livePollInFlight=true;
        io.execute(()->{
            try{
                JSONObject status=new JSONObject(watchConnection.requestBlocking("GET","/v1/status","",8_000L));
                JSONObject workout=status.optJSONObject("workout");
                runOnUiThread(()->renderLiveStatus(workout,null));
            }catch(Exception error){
                runOnUiThread(()->renderLiveStatus(null,error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()));
            }finally{livePollInFlight=false;}
        });
    }

    private void renderLiveStatus(JSONObject workout,String error){
        if(liveState==null)return;
        if(workout==null){
            rebuildLiveActions(error!=null?"unavailable":"idle");
            if(liveRing!=null)liveRing.set(0f,Palette.MOVE,Palette.ORANGE);
            liveState.setText(error!=null?"无法读取手表状态":"未在训练");
            liveState.setTextColor(Palette.TEXT_DIM);
            liveTime.setText("--:--");
            liveMeta.setText(error!=null?"请检查连接后重试":"在手表上开始，或点击下方按钮远程开始当前安排");
            return;
        }
        String state=workout.optString("state");
        rebuildLiveActions(state);
        boolean paused="PAUSED".equals(state);
        liveState.setText("PREPARING".equals(state)?"准备中":paused?"已暂停":("COMPLETED".equals(workout.optString("planState"))?"自由记录中":"训练中"));
        liveState.setTextColor(paused?Palette.YELLOW:Palette.GREEN);
        liveTime.setText(PhoneFormat.duration(workout.optLong("activeDurationMs")));
        StringBuilder meta=new StringBuilder(PhoneFormat.distance(workout.optDouble("distanceMeters",0)));
        long avgPace=workout.optLong("avgPaceSecondsPerKm");
        if(avgPace>0)meta.append(" · ").append(PhoneFormat.paceSeconds(avgPace));
        int heart=workout.optInt("heartRate");
        if(heart>0)meta.append(" · ").append(heart).append(" bpm");
        int stageCount=workout.optInt("stageCount");
        if(stageCount>0)meta.append(" · ").append(workout.optString("stageName")).append(" ").append(workout.optInt("stageNumber")).append("/").append(stageCount);
        liveMeta.setText(meta.toString());
        if(liveRing!=null){
            boolean planDone="COMPLETED".equals(workout.optString("planState"));
            float fraction=planDone?1f:stageCount>0?(workout.optInt("stageNumber")-1f)/stageCount:0f;
            liveRing.set(fraction,Palette.MOVE,Palette.ORANGE);
        }
    }

    private void discoverWatch() {
        stopDiscovery();
        if(watchConnection==null||watchConnection.snapshot().primaryTransport==null)connection.setText("正在寻找手表…");
        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("watchintervals-discovery");
            multicastLock.setReferenceCounted(false); multicastLock.acquire();
        }
        nsdManager = (NsdManager)getSystemService(NSD_SERVICE);
        discoveryListener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) {}
            public void onStartDiscoveryFailed(String type, int code) { stopDiscovery(); }
            public void onStopDiscoveryFailed(String type, int code) { releaseMulticast(); }
            public void onDiscoveryStopped(String type) { releaseMulticast(); }
            public void onServiceLost(NsdServiceInfo info) {}
            public void onServiceFound(NsdServiceInfo info) {
                if (!info.getServiceType().startsWith("_watchintervals._tcp") || resolving) return;
                resolving = true;
                nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo service, int code) { resolving=false; }
                    public void onServiceResolved(NsdServiceInfo service) {
        String address = service.getHost() == null ? "" : service.getHost().getHostAddress();
                        String credential = watchConnection.identity().lanCredential();if(credential.isEmpty())credential=code.getText().toString().trim();final String pairing=credential;
                        if (address.isEmpty()) { resolving=false; return; }
                        // The six-digit rule only applies to a first-time pairing code typed by the
                        // user. A paired phone holds a long-term LAN credential whose length is
                        // never 6, and checking it here told paired users to re-enter a code.
                        if (!watchConnection.identity().isPaired() && pairing.length() != 6) {
                            resolving=false;
                            runOnUiThread(() -> { host.setText(address); connection.setText("已发现手表，请输入配对码"); });
                            stopDiscovery(); return;
                        }
                        // A LAN can contain stale/debug advertisements. Verify the pairing API before replacing the saved host.
                        io.execute(() -> {
                            try {
                                JSONObject status = new JSONObject(new WatchClient(address, pairing).get("/v1/status"));
                                String discoveredId=status.optString("deviceId");String expectedId=getSharedPreferences("connection",MODE_PRIVATE).getString("watch_device_id","");
                                if(!expectedId.isEmpty()&&!expectedId.equals(discoveredId)){resolving=false;return;}
                                runOnUiThread(() -> {
                                    host.setText(address);
                                    getSharedPreferences("connection",MODE_PRIVATE).edit().putString("watch_device_id",discoveredId).apply();
                                    connection.setText("已发现 " + status.optString("device") + " · LAN 加速可用");
                                    stopDiscovery();
                                    syncAll();
                                });
                            } catch (Exception ignored) { resolving=false; }
                        });
                    }
                });
            }
        };
        try { nsdManager.discoverServices("_watchintervals._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener); }
        catch (Exception error) { stopDiscovery(); }
    }

    private void stopDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) { releaseMulticast(); }
        } else releaseMulticast();
        discoveryListener = null;
    }

    private void releaseMulticast() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }

    private void syncAll() {
        if(!fullSyncInFlight.compareAndSet(false,true)){
            setSyncState("同步正在进行，完成后会自动更新");
            return;
        }
        android.content.SharedPreferences.Editor connectionEdit=getSharedPreferences("connection", MODE_PRIVATE).edit().putString("host", host.getText().toString().trim());
        connectionEdit.remove("code").apply();
        setSyncBusy(true,PhoneSyncPolicy.progressLabel(0,4,"准备同步"));
        io.execute(() -> {
        try {
            String pairing=code.getText().toString().trim();if(!watchConnection.identity().isPaired()&&pairing.length()!=6)throw new IllegalArgumentException("请输入手表上的 6 位配对码");
            watchConnection.configurePairing(pairing);watchConnection.configureLan(host.getText().toString().trim(),pairing);
            // BLE is preferred but not required: with a verified LAN transport the request layer
            // routes around a failed connect on its own. Blocking the whole sync on connect()
            // was why history stayed on "连接后读取" while the MCP chain over LAN worked fine.
            try{watchConnection.connect().get(25,java.util.concurrent.TimeUnit.SECONDS);}
            catch(Exception bleError){
                if(!watchConnection.snapshot().lanAvailable)
                    throw new IllegalStateException("蓝牙连接失败，且局域网不可达；请靠近手表或连接同一 Wi-Fi",bleError);
            }
            setSyncState(PhoneSyncPolicy.progressLabel(1,4,"验证手表"));
            JSONObject status = new JSONObject(watchConnection.requestBlocking("GET","/v1/status","",20_000L));
            String expected=getSharedPreferences("connection",MODE_PRIVATE).getString("watch_device_id","");String actual=status.optString("deviceId");if(!expected.isEmpty()&&!expected.equals(actual))throw new IllegalStateException("发现的设备身份与已配对手表不一致");
            if(expected.isEmpty()&&!actual.isEmpty())getSharedPreferences("connection",MODE_PRIVATE).edit().putString("watch_device_id",actual).apply();
            setSyncState(PhoneSyncPolicy.progressLabel(2,4,"同步训练计划"));
            if(PhoneSyncOutbox.size(this)>0)PhoneSyncOutbox.drain(this,watchConnection);
            JSONObject plan = new JSONObject(watchConnection.requestBlocking("GET","/v1/plan/profile","",20_000L)); JSONArray history = new JSONArray(watchConnection.requestBlocking("GET","/v1/history","",20_000L));
            setSyncState(PhoneSyncPolicy.progressLabel(3,4,"刷新睡眠"));
            JSONObject sleepCandidate=null;
            boolean sleepHadRecords=false;
            try {
                JSONObject value=PhoneSleepSync.fetchRecent(watchConnection,31);
                if("ready".equals(value.optString("state"))) {
                    sleepHadRecords=value.optJSONArray("records")!=null
                            &&value.optJSONArray("records").length()>0;
                    sleepCandidate=PhoneSleepRepository.mergeAndSave(
                            this,value,System.currentTimeMillis());
                }
            } catch(Exception sleepError) {
                android.util.Log.w("PhoneMain","Sleep refresh did not block the main sync",
                        sleepError);
            }
            final JSONObject syncedSleep=sleepCandidate;
            final boolean syncedSleepHadRecords=sleepHadRecords;
            CloudSnapshotSync.syncAsync(this);
            long completedAt=System.currentTimeMillis();rememberLastSync(completedAt);
            syncRetryHandler.removeCallbacks(syncRetry);
            runOnUiThread(() -> {
                showPlan(plan);showHistory(history);
                if(currentSection==3&&syncedSleep!=null)showSleep(syncedSleep,!syncedSleepHadRecords,
                        syncedSleepHadRecords?null:"手表本次没有返回新记录，保留上次数据");
                setSyncBusy(false,PhoneSyncPolicy.successLabel(syncedSleepHadRecords,
                        new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date(completedAt))));
                ensureLocationRelay();
            });
        }catch(Exception error){
            String reason=userError(error);
            runOnUiThread(()->setSyncBusy(false,"同步未完成 · "+reason+" · 连接恢复后会重试"));
            PhoneSleepSyncWorker.schedule(this);
            syncRetryHandler.removeCallbacks(syncRetry);syncRetryHandler.postDelayed(syncRetry,30_000L);
        }finally{
            fullSyncInFlight.set(false);
            runOnUiThread(()->{if(syncAction!=null)syncAction.setEnabled(true);});
        }});
    }

    private void saveCloudConfig() {
        CloudSyncCredentials.Config current = CloudSyncCredentials.load(this);
        String key = cloudKey.getText().toString().trim();
        if (key.isEmpty()) key = current.deviceToken;
        if (!CloudSyncCredentials.save(this, cloudEndpoint.getText().toString(), key)) {
            Toast.makeText(this, "请输入 HTTPS /sync/v3/exchange 地址和有效设备 token", Toast.LENGTH_LONG).show();
            return;
        }
        cloudKey.setText(""); cloudKey.setHint("已安全保存设备 token");
        if (CloudSyncCredentials.readyForCloudV3(this)) {
            CloudSnapshotSync.syncAsync(this);
            Toast.makeText(this, "云同步配置已保存，正在后台测试", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "设备 token 保存失败", Toast.LENGTH_LONG).show();
        }
    }

    private void provisionCloudFromIntent(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) return;
        String endpoint = intent.getStringExtra("poyi_cloud_endpoint");
        String key = intent.getStringExtra("poyi_cloud_key");
        if (endpoint == null || key == null) return;
        if (CloudSyncCredentials.save(this, endpoint, key)) {
            cloudEndpoint.setText(endpoint); cloudKey.setText("");
            cloudKey.setHint("已安全保存设备 token");
            intent.removeExtra("poyi_cloud_endpoint"); intent.removeExtra("poyi_cloud_key");
            if (CloudSyncCredentials.readyForCloudV3(this)) CloudSnapshotSync.syncAsync(this);
        }
    }

    private void ensureBluetoothConnection(){
        if(android.os.Build.VERSION.SDK_INT>=31&&(checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)!=android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED)){
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT},REQUEST_BLUETOOTH);return;
        }
        startForegroundService(new Intent(this,PhoneCompanionService.class));
    }

    private String connectionLabel(WatchConnectionManager.Snapshot value){
        switch(value.state){case CONNECTED_BLE_LAN:return "蓝牙连接 · LAN 加速";case CONNECTED_BLE:return "蓝牙已连接";case CONNECTED_LAN:return "LAN 已连接 · 正在恢复蓝牙";case SCANNING:return "正在通过蓝牙寻找手表";case CONNECTING_BLE:case DISCOVERING_SERVICES:case SUBSCRIBING:case AUTHENTICATING:return "正在建立蓝牙连接";case BLUETOOTH_DISABLED:return "蓝牙已关闭";case UNPAIRED:return "请输入手表上的配对码";case BACKOFF:return "手表不在附近，稍后自动重连";default:return "尚未连接";}
    }
    private String transportLabel(WatchConnectionManager.Snapshot value){return value.primaryTransport==null?"连接可用":value.primaryTransport==com.poyi.watchintervals.phone.connection.TransportType.BLE?(value.lanAvailable?"蓝牙 · LAN 加速":"蓝牙"):"LAN";}

    private void ensureLocationRelay() {
        // Reached from async sync callbacks. A location-type FGS may only start while the app is
        // foreground (Android 14+); a late callback after onPause used to crash the process.
        if (!foreground) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION_RELAY);
            return;
        }
        try { startForegroundService(new android.content.Intent(this, PhoneLocationRelayService.class)); }
        catch (RuntimeException ignored) { /* Next successful sync retries. */ }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_LOCATION_RELAY && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) ensureLocationRelay();
        if(requestCode==REQUEST_BLUETOOTH&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)==android.content.pm.PackageManager.PERMISSION_GRANTED&&checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED)ensureBluetoothConnection();
    }

    private void showPlan(JSONObject profile) {
        watchCurrentPlanId=profile.optString("id");
        String name=profile.optString("name").trim(),group=profile.optString("group").trim();
        if(currentWatchPlan!=null)currentWatchPlan.setText(name.isEmpty()?"手表暂无安排":name);
        if(currentPlanSync!=null)currentPlanSync.setText(group.isEmpty()?"已从手表读取":group+" · 已在手表");
        // Never project a remote refresh into the editor. It used to silently overwrite a draft
        // during reconnect; explicit Edit is now the only path that changes form fields.
        renderSavedPlans();
    }

    private void renderPlan() {
        planList.removeAllViews();
        if(stages.isEmpty()){
            TextView empty=text("还没有训练阶段 · 从下方添加跑步、快走或休息",14,false,Palette.TEXT_DIM);
            empty.setGravity(Gravity.CENTER);planList.addView(empty,new LinearLayout.LayoutParams(-1,dp(64)));
            return;
        }
        for (int index=0; index<stages.size(); index++) {
            final int position=index; JSONObject stage=stages.get(index);
            LinearLayout stageCard=cardHigh(); stageCard.setPadding(dp(14),dp(12),dp(14),dp(12));
            String kind=stage.optString("kind"), unit=stage.optString("unit"); int target=stage.optInt("target");
            LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
            TextView order=text(String.format(Locale.CHINA,"%02d",position+1),13,true,Palette.TEXT_DIM);
            order.setGravity(Gravity.CENTER);order.setBackground(rounded(Palette.CARD_DEEP,12));
            header.addView(order,new LinearLayout.LayoutParams(dp(38),dp(38)));
            Button kindButton=button(kindName(kind),kindColor(kind),kindTextColor(kind));
            kindButton.setContentDescription("修改第"+(position+1)+"阶段类型，当前"+kindName(kind));
            LinearLayout.LayoutParams kindParams=new LinearLayout.LayoutParams(0,dp(48),1);kindParams.leftMargin=dp(8);header.addView(kindButton,kindParams);
            Button delete=button("移除",Color.TRANSPARENT,Palette.RED);delete.setContentDescription("移除第"+(position+1)+"阶段");
            header.addView(delete,new LinearLayout.LayoutParams(dp(64),dp(48)));stageCard.addView(header);
            TextView targetLabel=text("目标",12,true,Palette.TEXT_DIM);stageCard.addView(targetLabel);
            LinearLayout targetRow=new LinearLayout(this);targetRow.setGravity(Gravity.CENTER_VERTICAL);
            EditText value=input(""); value.setText(String.valueOf(Math.max(1,target))); value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            LinearLayout.LayoutParams valueParams=new LinearLayout.LayoutParams(0,dp(52),1);targetRow.addView(value,valueParams);
            Button unitButton=button("DISTANCE".equals(unit)?"距离 · 米":"时间 · 秒",Palette.CARD_HIGH,Palette.TEXT);unitButton.setTextSize(13);
            unitButton.setContentDescription("修改第"+(position+1)+"阶段目标单位，当前"+("DISTANCE".equals(unit)?"距离":"时间"));
            LinearLayout.LayoutParams unitParams=new LinearLayout.LayoutParams(dp(112),dp(52));unitParams.leftMargin=dp(8);targetRow.addView(unitButton,unitParams);
            stageCard.addView(targetRow);
            LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.END);
            Button up=button("前移",Palette.CARD_HIGH,Palette.TEXT_DIM);
            Button down=button("后移",Palette.CARD_HIGH,Palette.TEXT_DIM);
            up.setContentDescription("前移第"+(position+1)+"阶段");
            down.setContentDescription("后移第"+(position+1)+"阶段");
            up.setEnabled(position>0); down.setEnabled(position<stages.size()-1);
            actions.addView(up,new LinearLayout.LayoutParams(dp(76),dp(48)));
            LinearLayout.LayoutParams downParams=new LinearLayout.LayoutParams(dp(76),dp(48));downParams.leftMargin=dp(6);actions.addView(down,downParams);
            if(stages.size()>1)stageCard.addView(actions);
            value.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){
                    try{
                        if(s.length()==0){value.setError("请输入目标");return;}
                        int parsed=Integer.parseInt(s.toString());
                        if(parsed<1){value.setError("必须大于 0");return;}
                        stage.put("target",parsed);markPlanDraftDirty();
                    }catch(Exception invalid){value.setError("请输入有效数字");}
                }
                public void afterTextChanged(android.text.Editable value){}
            });
            kindButton.setOnClickListener(v->{try{
                String next=PhonePlanUiModel.nextKind(stage.optString("kind"));
                String nextUnit=PhonePlanUiModel.normalizedUnit(next,stage.optString("unit"));
                stage.put("kind",next).put("unit",nextUnit)
                        .put("target",PhonePlanUiModel.defaultTarget(next,nextUnit));
                markPlanDraftDirty();renderPlan();
            }catch(Exception ignored){}});
            unitButton.setEnabled(!"REST".equals(kind));
            unitButton.setOnClickListener(v->{ try{
                String from=stage.optString("unit"),to="DISTANCE".equals(from)?"TIME":"DISTANCE";
                stage.put("unit",to).put("target",PhonePlanUiModel.convertedTarget(
                        stage.optString("kind"),from,to,stage.optInt("target")));
                markPlanDraftDirty();renderPlan();
            }catch(Exception ignored){} });
            up.setOnClickListener(v->{if(position>0){JSONObject moved=stages.remove(position);stages.add(position-1,moved);markPlanDraftDirty();renderPlan();}});
            down.setOnClickListener(v->{if(position<stages.size()-1){JSONObject moved=stages.remove(position);stages.add(position+1,moved);markPlanDraftDirty();renderPlan();}});
            delete.setOnClickListener(v->{ stages.remove(position);markPlanDraftDirty();renderPlan(); });
            LinearLayout.LayoutParams params=margin();params.topMargin=dp(8);planList.addView(stageCard,params);
        }
    }

    private void addStage(String kind,String unit,int target){ try{ stages.add(new JSONObject().put("kind",kind).put("unit",unit).put("target",target));markPlanDraftDirty(); }catch(Exception ignored){} renderPlan(); }
    private void applyTemplate(boolean fartlek){ stages.clear(); try {
        if(fartlek){ if(planName.getText().toString().trim().isEmpty())planName.setText("变速跑安排"); planRequirement.setText("快跑 2 分钟，快走恢复 1 分钟，连续完成 6 组。"); for(int i=0;i<6;i++){stages.add(new JSONObject().put("kind","RUN").put("unit","TIME").put("target",120));stages.add(new JSONObject().put("kind","WALK").put("unit","TIME").put("target",60));} }
        else { if(planName.getText().toString().trim().isEmpty())planName.setText("距离间歇安排"); planRequirement.setText("跑步 1 千米，随后快走恢复 200 米；按阶段顺序完成。"); stages.add(new JSONObject().put("kind","RUN").put("unit","DISTANCE").put("target",1000)); stages.add(new JSONObject().put("kind","WALK").put("unit","DISTANCE").put("target",200)); }
    }catch(Exception ignored){} markPlanDraftDirty();renderPlan(); }

    private void newPlan(){
        editingPlanId="";detailPlanId="";suppressPlanDraftTracking=true;stages.clear();
        planName.setText(""); planGroup.setText("我的计划"); planRequirement.setText("");
        suppressPlanDraftTracking=false;addStage("RUN","DISTANCE",1000); showPlanEditor();planDraftDirty=true;planName.requestFocus();
        Toast.makeText(this,"已新建空白计划，请填写名称和分组",Toast.LENGTH_SHORT).show();
    }

    private void newPlanInGroup(JSONObject group){
        String groupId=group.optString("id"),groupName=group.optString("name");
        JSONObject library=PhonePlanLibrary.load(this);JSONArray plans=library.optJSONArray("plans");int day=1;
        if(plans!=null)for(int i=0;i<plans.length();i++){JSONObject item=plans.optJSONObject(i);if(item!=null&&groupId.equals(item.optString("groupId")))day++;}
        editingPlanId="";detailPlanId="";suppressPlanDraftTracking=true;stages.clear();planName.setText("第"+day+"天");planGroup.setText(groupName);
        planRequirement.setText("设置当天独立的跑步、快走与恢复内容。");
        suppressPlanDraftTracking=false;addStage("RUN","TIME",1200);showPlanEditor();planDraftDirty=true;planName.requestFocus();
    }

    private void showGroupNameDialog(JSONObject group,String initialName){
        EditText input=input("例如：30日减肥计划");input.setText(initialName);input.setSelectAllOnFocus(true);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
                .setTitle(group==null?"新建训练计划":"修改计划名称")
                .setView(input)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",null)
                .create();
        dialog.setOnShowListener(ignored->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("请输入计划名称");return;}
            try{
                if(group==null){JSONObject created=PhonePlanLibrary.createGroup(this,name);dialog.dismiss();newPlanInGroup(created);syncLibraryQuietly("训练计划已创建");}
                else{PhonePlanLibrary.renameGroup(this,group.optString("id"),name);dialog.dismiss();renderSavedPlans();syncLibraryQuietly("计划名称已更新");}
            }catch(Exception error){input.setError(error.getMessage());}
        }));
        dialog.show();
    }

    private void confirmDeleteGroup(JSONObject group){
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除“"+group.optString("name")+"”？")
                .setMessage("该计划中的安排会移动到“我的计划”，内容仍会保留。")
                .setNegativeButton("取消",null)
                .setPositiveButton("删除",(dialog,which)->{
                    try{PhonePlanLibrary.deleteGroup(this,group.optString("id"));renderSavedPlans();syncLibraryQuietly("训练计划已删除");}
                    catch(Exception error){setSyncState("删除计划失败 · "+userError(error));}
                }).show();
    }

    private void syncLibraryQuietly(String successText){
        JSONObject library=PhonePlanLibrary.load(this);
        queueAndSyncLibrary(library,successText);
    }

    private void queueAndSyncLibrary(JSONObject library,String successText){
        setSyncState("计划已保存 · 正在同步");
        runIo(()->{
            try {
                PhoneSyncOutbox.enqueueLibrary(this,library,"upsert","library");
                JSONObject sync=PhoneSyncOutbox.drain(this,watchConnection);
                JSONObject confirmed=null;
                if("synced".equals(sync.optString("state")))try{confirmed=new JSONObject(
                        watchConnection.requestBlocking("GET","/v1/plan/profile","",10_000L));}
                catch(Exception ignored){}
                JSONObject confirmedProfile=confirmed;
                runOnUiThread(()->{setSyncState("synced".equals(sync.optString("state"))
                        ?successText:"计划已保存 · 等待手表连接");if(confirmedProfile!=null)showPlan(confirmedProfile);});
            } catch(Exception error) {
                PhonePlanProjectionWorker.schedule(this);
                runOnUiThread(()->setSyncState("计划已保存 · 等待手表连接"));
            }
        });
    }

    private JSONArray copyStages(){
        JSONArray array=new JSONArray();
        for(JSONObject stage:stages) try{ array.put(new JSONObject(stage.toString())); }catch(Exception ignored){}
        return array;
    }

    private JSONObject saveLocalPlan(){
        String name=planName.getText().toString().trim(), group=planGroup.getText().toString().trim();
        if(name.isEmpty()){planName.setError("请填写安排名称");planName.requestFocus();return null;}
        if(group.isEmpty()){planGroup.setError("请选择所属训练计划");planGroup.requestFocus();return null;}
        if(stages.isEmpty()){Toast.makeText(this,"至少添加一项训练内容",Toast.LENGTH_SHORT).show();return null;}
        try{
            if(editingPlanId.isEmpty()) editingPlanId=UUID.randomUUID().toString();
            JSONObject item=new JSONObject().put("id",editingPlanId).put("name",name).put("group",group)
                    .put("requirement",planRequirement.getText().toString().trim()).put("stages",copyStages());
            JSONObject library=PhonePlanLibrary.upsert(this,item);renderSavedPlans();
            return findPlan(library,editingPlanId);
        }catch(Exception error){Toast.makeText(this,"保存失败："+userError(error),Toast.LENGTH_LONG).show();return null;}
    }

    private void saveEditedPlan(){
        JSONObject saved=saveLocalPlan();if(saved==null)return;
        planDraftDirty=false;detailPlanId=editingPlanId;
        JSONObject library=PhonePlanLibrary.load(this);
        queueAndSyncLibrary(library,"安排已同步到手表");
        showPlanDetail(saved);
        Toast.makeText(this,"安排已保存",Toast.LENGTH_SHORT).show();
    }

    private void renderSavedPlans(){
        if(savedPlanList==null)return; savedPlanList.removeAllViews(); JSONObject library=PhonePlanLibrary.load(this);JSONArray plans=library.optJSONArray("plans");
        if(plans==null)plans=new JSONArray();
        if(plans.length()==0){
            LinearLayout empty=card();empty.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView title=text("还没有训练安排",17,true,Palette.TEXT);title.setGravity(Gravity.CENTER);
            TextView body=text("先建立第 1 天，之后可以继续按训练周期分组。",13,false,Palette.TEXT_DIM);body.setGravity(Gravity.CENTER);
            Button first=button("新建第 1 个安排",Palette.EXERCISE,Palette.INK);
            empty.addView(title);empty.addView(body);empty.addView(first,new LinearLayout.LayoutParams(-1,dp(48)));
            first.setOnClickListener(v->newPlan());savedPlanList.addView(empty,margin());return;
        }
        JSONArray groups=library.optJSONArray("groups");java.util.HashSet<String> rendered=new java.util.HashSet<>();
        if(groups!=null)for(int groupIndex=0;groupIndex<groups.length();groupIndex++){
            JSONObject group=groups.optJSONObject(groupIndex);if(group==null)continue;String groupId=group.optString("id");
            int arrangementCount=0;for(int i=0;i<plans.length();i++){JSONObject item=plans.optJSONObject(i);if(item!=null&&groupId.equals(item.optString("groupId")))arrangementCount++;}
            if(arrangementCount==0)continue;
            LinearLayout planBlock=card();planBlock.setPadding(dp(16),dp(14),dp(16),dp(14));planBlock.setBackground(rounded(Palette.CARD,22));
            LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView header=text(group.optString("name"),19,true,Palette.TEXT);header.setSingleLine(false);titleRow.addView(header,new LinearLayout.LayoutParams(0,-2,1));
            TextView count=text(arrangementCount+" 个安排",12,false,Palette.TEXT_DIM);count.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);titleRow.addView(count,new LinearLayout.LayoutParams(dp(82),dp(38)));
            planBlock.addView(titleRow);
            LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
            Button addDay=button("＋ 添加安排",Palette.FILL_RUN,Palette.EXERCISE);actions.addView(addDay,new LinearLayout.LayoutParams(0,dp(48),1));
            Button rename=button("编辑",Palette.CARD_HIGH,Palette.TEXT_DIM);actions.addView(rename,new LinearLayout.LayoutParams(dp(68),dp(48)));
            Button delete=button("删除",Color.TRANSPARENT,Palette.RED);actions.addView(delete,new LinearLayout.LayoutParams(dp(62),dp(48)));
            planBlock.addView(actions,new LinearLayout.LayoutParams(-1,dp(54)));
            addDay.setOnClickListener(v->newPlanInGroup(group));rename.setOnClickListener(v->showGroupNameDialog(group,group.optString("name")));delete.setOnClickListener(v->confirmDeleteGroup(group));
            for(int i=0;i<plans.length();i++){JSONObject item=plans.optJSONObject(i);if(item!=null&&groupId.equals(item.optString("groupId"))){addSavedPlanRow(planBlock,library,item);rendered.add(item.optString("id"));}}
            LinearLayout.LayoutParams blockParams=margin();blockParams.topMargin=dp(10);savedPlanList.addView(planBlock,blockParams);
        }
        LinearLayout ungrouped=null;
        for(int i=0;i<plans.length();i++){
            JSONObject item=plans.optJSONObject(i);if(item==null||rendered.contains(item.optString("id")))continue;
            if(ungrouped==null){
                ungrouped=card();ungrouped.setPadding(dp(16),dp(14),dp(16),dp(14));
                ungrouped.addView(text("未分组",19,true,Palette.TEXT));
                savedPlanList.addView(ungrouped,margin());
            }
            addSavedPlanRow(ungrouped,library,item);
        }
    }

    private void addSavedPlanRow(LinearLayout parent,JSONObject library,JSONObject item){
        String id=item.optString("id");JSONArray savedStages=item.optJSONArray("stages");
        boolean selected=id.equals(library.optString("selectedPlanId"));boolean onWatch=id.equals(watchCurrentPlanId);
        LinearLayout open=section();open.setPadding(dp(14),dp(11),dp(12),dp(11));
        open.setBackground(clickableSurface(selected?Palette.FILL_SELECTED:Palette.CARD_HIGH,16,
                selected?Palette.EXERCISE:Palette.BORDER));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text(item.optString("name"),16,true,Palette.TEXT),new LinearLayout.LayoutParams(0,dp(30),1));
        if(onWatch||selected){
            TextView state=text(onWatch?"手表当前":"手机已选",11,true,onWatch?Palette.GREEN:Palette.EXERCISE);
            state.setGravity(Gravity.CENTER);state.setBackground(rounded(Palette.CARD,12));
            titleRow.addView(state,new LinearLayout.LayoutParams(dp(onWatch?68:62),dp(28)));
        }
        open.addView(titleRow);
        TextView detail=text(PhonePlanUiModel.summary(savedStages),12,false,Palette.TEXT_DIM);open.addView(detail);
        TextView sequence=text(PhonePlanUiModel.compactSequence(savedStages),12,false,Palette.TEXT_DIM);
        sequence.setSingleLine(true);sequence.setEllipsize(android.text.TextUtils.TruncateAt.END);open.addView(sequence);
        open.setClickable(true);open.setFocusable(true);open.setContentDescription(item.optString("name")+"，"+PhonePlanUiModel.summary(savedStages)+"，查看详情");
        open.setOnClickListener(v->showPlanDetail(item));
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,dp(96));rowParams.topMargin=dp(7);parent.addView(open,rowParams);
    }

    private void showPlanDetail(JSONObject item){
        if(item==null){showPlanLibrary();return;}
        detailPlanId=item.optString("id");JSONObject library=PhonePlanLibrary.load(this);
        JSONArray array=item.optJSONArray("stages");
        planDetailName.setText(item.optString("name"));
        planDetailMeta.setText(PhonePlanLibrary.groupName(library,item.optString("groupId"))+" · "+PhonePlanUiModel.summary(array));
        planDetailSequence.setText(PhonePlanUiModel.compactSequence(array));
        String requirement=item.optString("requirement").trim();
        planDetailRequirement.setText(requirement.isEmpty()?"按阶段顺序完成训练。":requirement);
        planDetailStages.removeAllViews();
        if(array!=null)for(int index=0;index<array.length();index++){
            JSONObject stage=array.optJSONObject(index);if(stage==null)continue;
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView number=text(String.format(Locale.CHINA,"%02d",index+1),12,true,Palette.TEXT_DIM);number.setGravity(Gravity.CENTER);
            row.addView(number,new LinearLayout.LayoutParams(dp(38),dp(44)));
            row.addView(text(PhonePlanUiModel.stageLabel(stage),15,true,Palette.TEXT),new LinearLayout.LayoutParams(0,dp(44),1));
            row.setBackground(rounded(index%2==0?Palette.CARD_HIGH:Palette.CARD,12));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(44));params.topMargin=dp(4);planDetailStages.addView(row,params);
        }
        planLibraryPanel.setVisibility(View.GONE);planEditorPanel.setVisibility(View.GONE);planDetailPanel.setVisibility(View.VISIBLE);
        if(planScroll!=null)planScroll.smoothScrollTo(0,0);
    }

    private void editDetailPlan(){
        JSONObject item=findPlan(PhonePlanLibrary.load(this),detailPlanId);if(item==null)return;
        suppressPlanDraftTracking=true;
        editingPlanId=item.optString("id");planName.setText(item.optString("name"));
        planGroup.setText(PhonePlanLibrary.groupName(PhonePlanLibrary.load(this),item.optString("groupId")));
        planRequirement.setText(item.optString("requirement"));
        JSONArray array=item.optJSONArray("stages");stages.clear();if(array!=null)for(int i=0;i<array.length();i++)try{stages.add(new JSONObject(array.getJSONObject(i).toString()));}catch(Exception ignored){}
        suppressPlanDraftTracking=false;planDraftDirty=false;renderPlan();showPlanEditor();
    }

    private void showPlanLibrary(){
        if(planLibraryPanel!=null)planLibraryPanel.setVisibility(View.VISIBLE);
        if(planDetailPanel!=null)planDetailPanel.setVisibility(View.GONE);
        if(planEditorPanel!=null)planEditorPanel.setVisibility(View.GONE);renderSavedPlans();
        if(planScroll!=null)planScroll.smoothScrollTo(0,0);
    }

    private void showPlanEditor(){
        if(planLibraryPanel!=null)planLibraryPanel.setVisibility(View.GONE);
        if(planDetailPanel!=null)planDetailPanel.setVisibility(View.GONE);
        if(planEditorPanel!=null)planEditorPanel.setVisibility(View.VISIBLE);
        planEditorTitle.setText(editingPlanId.isEmpty()?"新建安排":"编辑安排");
        if(planScroll!=null)planScroll.smoothScrollTo(0,0);
    }

    private void selectPlan(String id){
        if(id==null||id.isEmpty())return;
        try{PhonePlanLibrary.select(this,id);JSONObject library=PhonePlanLibrary.load(this);
            if(currentPlanSync!=null)currentPlanSync.setText("手机已选择 · 等待手表确认");
            renderSavedPlans();queueAndSyncLibrary(library,"当前安排已同步到手表");
            JSONObject item=findPlan(library,id);if(item!=null)showPlanDetail(item);
        }catch(Exception error){setSyncState("设为当前失败 · "+userError(error));}
    }

    private void confirmDeletePlan(String id){
        JSONObject item=findPlan(PhonePlanLibrary.load(this),id);if(item==null)return;
        new android.app.AlertDialog.Builder(this).setTitle("删除“"+item.optString("name")+"”？")
                .setMessage("该安排会从手机、云端和手表计划库中移除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->deleteSavedPlan(id)).show();
    }

    private void deleteSavedPlan(String id){
        try{PhonePlanLibrary.deletePlan(this,id);if(id.equals(editingPlanId))editingPlanId="";
            detailPlanId="";showPlanLibrary();syncLibraryQuietly("安排已删除并同步");
        }catch(Exception error){setSyncState("删除安排失败 · "+userError(error));}
    }

    private JSONObject findPlan(JSONObject library,String id){
        JSONArray plans=library==null?null:library.optJSONArray("plans");
        if(plans!=null)for(int index=0;index<plans.length();index++){
            JSONObject item=plans.optJSONObject(index);
            if(item!=null&&id.equals(item.optString("id")))return item;
        }
        return null;
    }

    private void markPlanDraftDirty(){
        if(!suppressPlanDraftTracking&&planEditorPanel!=null
                &&planEditorPanel.getVisibility()==View.VISIBLE)planDraftDirty=true;
    }

    private void leavePlanEditor(){
        if(!planDraftDirty){
            JSONObject detail=findPlan(PhonePlanLibrary.load(this),detailPlanId);
            if(detail==null)showPlanLibrary();else showPlanDetail(detail);
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("放弃未保存的修改？")
                .setMessage("返回后，本次对名称和阶段的修改不会保留。")
                .setNegativeButton("继续编辑",null)
                .setPositiveButton("放弃",(dialog,which)->{
                    planDraftDirty=false;JSONObject detail=findPlan(PhonePlanLibrary.load(this),detailPlanId);
                    if(detail==null)showPlanLibrary();else showPlanDetail(detail);
                }).show();
    }

    @Override public void onBackPressed(){
        if(setupScroll!=null&&setupScroll.getVisibility()==View.VISIBLE){toggleSetup(false);return;}
        if(planEditorPanel!=null&&planEditorPanel.getVisibility()==View.VISIBLE){leavePlanEditor();return;}
        if(planDetailPanel!=null&&planDetailPanel.getVisibility()==View.VISIBLE){showPlanLibrary();return;}
        super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        if(planEditorPanel!=null&&planEditorPanel.getVisibility()==View.VISIBLE){
            state.putString("plan_mode","editor");state.putString("editing_plan_id",editingPlanId);
            state.putString("detail_plan_id",detailPlanId);state.putString("draft_name",planName.getText().toString());
            state.putString("draft_group",planGroup.getText().toString());
            state.putString("draft_requirement",planRequirement.getText().toString());
            state.putString("draft_stages",copyStages().toString());state.putBoolean("draft_dirty",planDraftDirty);
        }else if(planDetailPanel!=null&&planDetailPanel.getVisibility()==View.VISIBLE){
            state.putString("plan_mode","detail");state.putString("detail_plan_id",detailPlanId);
        }
        state.putInt("phone_section",currentSection);
    }

    private void restoreUiState(Bundle state){
        if(state==null)return;
        int section=Math.max(0,Math.min(3,state.getInt("phone_section",0)));showSection(section);
        String mode=state.getString("plan_mode","");
        if("detail".equals(mode)){
            JSONObject detail=findPlan(PhonePlanLibrary.load(this),state.getString("detail_plan_id",""));
            if(detail!=null)showPlanDetail(detail);
            return;
        }
        if(!"editor".equals(mode))return;
        suppressPlanDraftTracking=true;editingPlanId=state.getString("editing_plan_id","");
        detailPlanId=state.getString("detail_plan_id","");planName.setText(state.getString("draft_name",""));
        planGroup.setText(state.getString("draft_group",""));
        planRequirement.setText(state.getString("draft_requirement",""));stages.clear();
        try{JSONArray saved=new JSONArray(state.getString("draft_stages","[]"));for(int index=0;index<saved.length();index++)stages.add(new JSONObject(saved.getJSONObject(index).toString()));}
        catch(Exception ignored){}
        suppressPlanDraftTracking=false;planDraftDirty=state.getBoolean("draft_dirty",true);renderPlan();showPlanEditor();
    }
    private void control(String action){ runIo(()->{ try {
        String expected="pause".equals(action)?"RUNNING":"resume".equals(action)?"PAUSED":"start".equals(action)?"STOPPED":"";
        JSONObject command=new JSONObject().put("commandId",java.util.UUID.randomUUID().toString()).put("expiresAt",System.currentTimeMillis()+30_000L);
        if(!expected.isEmpty())command.put("expectedState",expected);
        watchConnection.requestBlocking("POST","/v1/control/"+action,command.toString(),30_000L); runOnUiThread(()->liveMeta.setText("操作已发送到手表"));
    } catch(Exception error){runOnUiThread(()->liveMeta.setText("操作失败 · "+userError(error)));} }); }

    private void showHistory(JSONArray array){
        historyList.removeAllViews(); historySummary.setText(array.length()+" 次训练 · 点击查看地图轨迹与完整数据");
        for(int i=0;i<array.length();i++){
            JSONObject record=array.optJSONObject(i); if(record==null)continue;
            LinearLayout row=cardHigh(); row.setPadding(dp(16),dp(14),dp(16),dp(14));
            TextView date=text(new SimpleDateFormat("MM月dd日  HH:mm",Locale.CHINA).format(new Date(record.optLong("startedAt"))),15,true,Palette.TEXT_DIM);
            row.addView(date);
            long duration=record.optLong("durationMs"); double meters=record.optDouble("distanceMeters");
            TextView primary=text(PhoneFormat.distance(meters)+"  ·  "+PhoneFormat.duration(duration)+"  ·  "+PhoneFormat.pace(duration,meters),17,true,Palette.TEXT);
            row.addView(primary);
            TextView secondary=text(record.optInt("steps")+" 步  ·  平均心率 "+(record.optInt("averageHeartRate")>0?record.optInt("averageHeartRate")+" bpm":"--")+"  ·  "+record.optInt("routePointCount")+" 个轨迹点",13,false,Palette.TEXT_DIM);
            row.addView(secondary);
            row.setOnClickListener(v->runIo(()->{try{String detail=watchConnection.requestBlocking("GET","/v1/history/"+android.net.Uri.encode(record.optString("id")),"",20_000L);runOnUiThread(()->startActivity(new Intent(this,HistoryDetailActivity.class).putExtra("record",detail)));}catch(Exception error){runOnUiThread(()->historySummary.setText("读取详情失败 · "+userError(error)+" · 点击可重试"));}}));
            LinearLayout.LayoutParams params=margin(); params.setMargins(0,dp(10),0,0); historyList.addView(row,params);
        }
    }

    private void loadSleep(){
        final JSONObject cached=PhoneSleepRepository.load(this);
        if(cached!=null)showSleep(cached,true,null);
        else showSleepEmpty("本机还没有已同步的睡眠数据", "连接手表并同步一次后，这里可离线查看最近 31 天。");
        WatchConnectionManager.Snapshot snapshot=watchConnection==null?null:watchConnection.snapshot();
        boolean transportReady=snapshot!=null
                && (snapshot.primaryTransport!=null||snapshot.lanAvailable);
        if(!transportReady){
            if(cached!=null)showSleep(cached,true,"当前离线");
            return;
        }
        sleepSummary.setText(cached==null?"正在从手表读取最近 31 天…":cacheLabel(cached)+" · 正在后台刷新");
        io.execute(()->{try{
            JSONObject result=PhoneSleepSync.fetchRecent(watchConnection,31);
            if("ready".equals(result.optString("state"))){
                boolean receivedRecords=result.optJSONArray("records")!=null
                        &&result.optJSONArray("records").length()>0;
                JSONObject saved=PhoneSleepRepository.mergeAndSave(
                        this,result,System.currentTimeMillis());
                runOnUiThread(()->showSleep(saved,!receivedRecords,receivedRecords
                        ?null:"手表本次没有返回新记录，保留上次数据"));
            }else runOnUiThread(()->showSleepUnavailable(result,cached));
        }catch(Exception error){
            String reason=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            runOnUiThread(()->{
                if(cached!=null)showSleep(cached,true,"刷新失败："+reason);
                else showSleepEmpty("暂时无法读取手表",reason);
            });
        }});
    }

    private void showSleepUnavailable(JSONObject result,JSONObject cached){
        String state=result.optString("state");
        String reason="permission_required".equals(state)
                ?"请在手表端打开步序并允许读取睡眠"
                :"系统睡眠暂不可用："+result.optString("error","未知错误");
        if(cached!=null)showSleep(cached,true,reason);
        else showSleepEmpty("暂无可显示的睡眠记录",reason);
    }

    private void showSleep(JSONObject result,boolean cached,String note){
        sleepList.removeAllViews();
        JSONArray records=result.optJSONArray("records");if(records==null)records=new JSONArray();
        String summary=(cached?cacheLabel(result):"刚刚从手表更新并保存到本机")
                +" · "+records.length()+" 晚";
        sleepSummary.setText(note==null||note.isEmpty()?summary:summary+" · "+note);
        if(records.length()==0){
            showSleepEmpty("最近没有睡眠记录", "系统已返回空列表；没有用估算数据补齐。");
            return;
        }
        PhoneSleepWeek week=PhoneSleepWeek.from(records);
        if(!week.nights.isEmpty()){
            LinearLayout trend=card();trend.setPadding(dp(16),dp(14),dp(16),dp(12));
            trend.addView(text("近 7 晚",17,true,Palette.TEXT));
            trend.addView(text("柱顶为小时数；数据来自本机最近一次成功同步",12,false,Palette.TEXT_DIM));
            SleepWeekTrendView chart=new SleepWeekTrendView(this);chart.setWeek(week);
            trend.addView(chart,new LinearLayout.LayoutParams(-1,dp(154)));
            LinearLayout.LayoutParams trendParams=margin();trendParams.topMargin=dp(12);sleepList.addView(trend,trendParams);
        }
        for(int i=0;i<records.length();i++){
            JSONObject record=records.optJSONObject(i);if(record==null)continue;
            PhoneSleepOverview overview=PhoneSleepOverview.from(record);
            PhoneSleepTimeline timeline=PhoneSleepTimeline.from(record);
            LinearLayout row=card();row.setPadding(dp(16),dp(15),dp(16),dp(16));
            long displayTime=timeline.available()?timeline.endTime:overview.timestamp;
            String date=displayTime>0L
                    ?new SimpleDateFormat("MM月dd日",Locale.CHINA).format(new Date(displayTime))
                    :"日期未返回";
            if(timeline.available())date+="  ·  "+new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date(timeline.startTime))
                    +" – "+new SimpleDateFormat("HH:mm",Locale.CHINA).format(new Date(timeline.endTime));
            row.addView(text(date,15,true,Palette.TEXT_DIM));

            LinearLayout hero=new LinearLayout(this);hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.addView(sleepHero("睡眠评分",overview.scoreAvailable
                    ?overview.sleepScore+" 分":"--"),weight());
            hero.addView(sleepHero("总时长",overview.durationAvailable
                    ?sleepMinutes(overview.totalDurationMinutes):"--"),weight());
            row.addView(hero);

            row.addView(text("阶段时间线",15,true,Palette.TEXT));
            if(timeline.available()){
                SleepStageTimelineView timelineView=new SleepStageTimelineView(this);timelineView.setTimeline(timeline);
                LinearLayout.LayoutParams timelineParams=new LinearLayout.LayoutParams(-1,dp(154));
                timelineParams.setMargins(0,dp(4),0,dp(8));row.addView(timelineView,timelineParams);
                if(timeline.unknownCount()>0)row.addView(text(timeline.unknownCount()+" 段厂商未知阶段以灰色保留，没有猜测成深睡或 REM。",12,false,Palette.TEXT_DIM));
            }else row.addView(text("系统没有返回有效的阶段起止时间，本晚只显示时长构成。",13,false,Palette.TEXT_DIM));
            row.addView(text("阶段构成",15,true,Palette.TEXT));
            if(overview.stageBreakdownAvailable){
                SleepStageBarView chart=new SleepStageBarView(this);chart.setOverview(overview);
                LinearLayout.LayoutParams chartParams=new LinearLayout.LayoutParams(-1,dp(18));
                chartParams.setMargins(0,dp(7),0,dp(8));row.addView(chart,chartParams);
            }else{
                row.addView(text("系统未返回完整的深睡、浅睡、REM 与清醒时长，比例图已隐藏。",13,false,Palette.TEXT_DIM));
            }
            LinearLayout first=new LinearLayout(this),second=new LinearLayout(this);
            first.addView(sleepStageMetric("深睡",overview.deepMinutes,
                    overview.deepAvailable,Palette.SLEEP_DEEP),weight());
            first.addView(sleepStageMetric("浅睡",overview.lightMinutes,
                    overview.lightAvailable,Palette.SLEEP_LIGHT),weight());
            second.addView(sleepStageMetric("REM",overview.remMinutes,
                    overview.remAvailable,Palette.SLEEP_REM),weight());
            second.addView(sleepStageMetric("清醒",overview.awakeMinutes,
                    overview.awakeAvailable,Palette.SLEEP_AWAKE),weight());
            row.addView(first);row.addView(second);
            if(overview.durationAvailable&&overview.stageTotalMinutes()>0L
                    &&Math.abs(overview.totalDurationMinutes-overview.stageTotalMinutes())>10L){
                row.addView(text("系统总时长 "+sleepMinutes(overview.totalDurationMinutes)+"，阶段合计 "
                        +sleepMinutes(overview.stageTotalMinutes())+"；两组原始字段不一致，均按原值展示。",12,false,Palette.ORANGE));
            }

            ArrayList<String> health=new ArrayList<>();
            if(overview.spo2Available)health.add("平均血氧 "+overview.spo2AveragePercent+"%");
            if(overview.heartRateAvailable)health.add("睡眠心率 "+overview.heartRateBenchmarkBpm+" bpm");
            if(overview.breathRateAvailable)health.add("呼吸 "+String.format(Locale.CHINA,"%.1f 次/分",overview.breathRateBenchmarkPerMinute));
            row.addView(text(health.isEmpty()?"血氧、心率与呼吸数据未返回":android.text.TextUtils.join(" · ",health),13,false,Palette.TEXT_DIM));
            row.addView(text(overview.sessionCount+" 段睡眠 · "+overview.rawStageCount+" 个系统原始阶段",12,false,Palette.HINT));
            LinearLayout.LayoutParams params=margin();params.topMargin=dp(12);sleepList.addView(row,params);
        }
    }

    private void showSleepEmpty(String title,String detail){
        sleepList.removeAllViews();
        LinearLayout empty=card();empty.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heading=text(title,16,true,Palette.TEXT);heading.setGravity(Gravity.CENTER);
        TextView body=text(detail,13,false,Palette.TEXT_DIM);body.setGravity(Gravity.CENTER);
        empty.addView(heading);empty.addView(body);
        LinearLayout.LayoutParams params=margin();params.topMargin=dp(14);sleepList.addView(empty,params);
        sleepSummary.setText(detail);
    }

    private String cacheLabel(JSONObject cached){
        long cachedAt=cached.optLong("cachedAt");
        return cachedAt>0L?"本机数据 · "+new SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA)
                .format(new Date(cachedAt))+" 同步":"本机已保存的数据";
    }

    private TextView sleepHero(String label,String value){
        TextView view=text(label+"\n"+value,20,true,Palette.TEXT);view.setGravity(Gravity.CENTER);
        view.setLineSpacing(dp(4),1f);view.setBackground(rounded(Palette.CARD_HIGH,16));
        view.setPadding(dp(8),dp(12),dp(8),dp(12));return view;
    }

    private LinearLayout sleepStageMetric(String label,long minutes,boolean available,int color){
        LinearLayout box=new LinearLayout(this);box.setGravity(Gravity.CENTER_VERTICAL);
        View dot=new View(this);dot.setBackground(rounded(color,8));
        LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(dp(9),dp(9));
        dotParams.rightMargin=dp(7);box.addView(dot,dotParams);
        box.addView(text(label+"  "+(available?sleepMinutes(minutes):"--"),13,true,Palette.TEXT));return box;
    }

    private String sleepMinutes(long value){
        return PhoneFormat.minutesHuman((int)Math.min(Integer.MAX_VALUE,Math.max(0L,value)));
    }
    private void runIo(Throwing action){io.execute(()->{try{action.run();}catch(Exception error){
        runOnUiThread(()->setSyncState("操作未完成 · "+userError(error)));
    }});}
    interface Throwing{void run()throws Exception;}
    private void setSyncState(String value){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){
            runOnUiThread(()->setSyncState(value));return;
        }
        if(syncSummary!=null)syncSummary.setText(value);
    }
    private void setSyncBusy(boolean busy,String value){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){
            runOnUiThread(()->setSyncBusy(busy,value));return;
        }
        if(syncAction!=null){syncAction.setEnabled(!busy);syncAction.setText(busy?"同步中":"同步");}
        setSyncState(value);
    }
    private String lastSyncLabel(){
        long value=getSharedPreferences("phone_sync_ui",MODE_PRIVATE).getLong("last_full_sync_at",0L);
        return value<=0L?"已同步的数据会保存在本机":"上次完整同步 · "+new SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(new Date(value));
    }
    private void rememberLastSync(long value){
        getSharedPreferences("phone_sync_ui",MODE_PRIVATE).edit().putLong("last_full_sync_at",Math.max(0L,value)).apply();
    }
    private String userError(Throwable error){
        Throwable cause=error;while(cause!=null&&cause.getCause()!=null)cause=cause.getCause();
        String message=cause==null?"":String.valueOf(cause.getMessage());String lower=message.toLowerCase(Locale.ROOT);
        if(lower.contains("timeout"))return "请求超时";
        if(lower.contains("pair")||lower.contains("401"))return "手表配对需要重新确认";
        if(lower.contains("bluetooth")||lower.contains("ble")||lower.contains("gatt")||lower.contains("offline"))return "手表连接中断";
        if(message.matches(".*[\u4e00-\u9fa5].*")&&message.length()<=80)return message;
        return "暂时无法完成，请稍后重试";
    }
    private String kindName(String kind){return PhonePlanUiModel.kindName(kind);}
    private int kindColor(String kind){return "WALK".equals(kind)?Palette.FILL_WALK:"REST".equals(kind)?Palette.FILL_REST:Palette.FILL_RUN;}
    private int kindTextColor(String kind){return "WALK".equals(kind)?Palette.STAND:"REST".equals(kind)?Palette.YELLOW:Palette.EXERCISE;}
    private LinearLayout section(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(){LinearLayout v=section();v.setPadding(dp(18),dp(16),dp(18),dp(16));v.setBackground(roundedStroke(Palette.CARD,22,Palette.BORDER,1));v.setElevation(dp(1));return v;}
    private LinearLayout compactCard(){LinearLayout v=section();v.setPadding(dp(14),dp(10),dp(14),dp(10));v.setBackground(roundedStroke(Palette.CARD,20,Palette.BORDER,1));return v;}
    private LinearLayout cardHigh(){LinearLayout v=card();v.setBackground(roundedStroke(Palette.CARD,18,Palette.BORDER,1));return v;}
    private LinearLayout.LayoutParams margin(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(16);return p;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(6),dp(3),dp(6));return p;}
    private TextView text(String s,int sp,boolean bold,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);v.setTypeface(null,bold?Typeface.BOLD:Typeface.NORMAL);v.setPadding(0,dp(5),0,dp(5));return v;}
    private LinearLayout pageTitle(String title,String subtitle){LinearLayout box=section();TextView heading=text(title,34,true,Palette.TEXT);heading.setMinHeight(dp(46));heading.setLetterSpacing(.01f);box.addView(heading,new LinearLayout.LayoutParams(-1,-2));TextView detail=text(subtitle,14,false,Palette.TEXT_DIM);detail.setMinHeight(dp(28));box.addView(detail,new LinearLayout.LayoutParams(-1,-2));return box;}
    private EditText input(String hint){EditText v=new EditText(this);v.setHint(hint);v.setTextSize(16);v.setSingleLine(true);v.setTextColor(Palette.TEXT);v.setHintTextColor(Palette.HINT);v.setMinHeight(dp(54));v.setPadding(dp(14),dp(10),dp(14),dp(10));v.setBackground(rounded(Palette.CARD_HIGH,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);v.setLayoutParams(p);return v;}
    private Button button(String s,int bg,int fg){Button v=new Button(this);v.setText(s);v.setTextSize(15);v.setTextColor(fg);v.setMinHeight(dp(48));v.setBackground(rippleSurface(bg,16,Color.TRANSPARENT,0));v.setAllCaps(false);v.setStateListAnimator(null);return v;}
    private GradientDrawable rounded(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private GradientDrawable roundedStroke(int color,int radius,int strokeColor,int strokeWidth){GradientDrawable shape=rounded(color,radius);shape.setStroke(dp(strokeWidth),strokeColor);return shape;}
    private android.graphics.drawable.Drawable clickableSurface(int color,int radius,int strokeColor){return rippleSurface(color,radius,strokeColor,1);}
    private android.graphics.drawable.Drawable rippleSurface(int color,int radius,int strokeColor,int strokeWidth){
        GradientDrawable content=strokeWidth>0?roundedStroke(color,radius,strokeColor,strokeWidth):rounded(color,radius);
        GradientDrawable mask=rounded(Color.WHITE,radius);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(
                Color.argb(34,0,0,0)),content,mask);
    }
    private GradientDrawable glassSurface(int radius){GradientDrawable shape=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Palette.GLASS_TOP,Palette.GLASS_BOTTOM});shape.setCornerRadius(dp(radius));shape.setStroke(dp(1),Palette.GLASS_BORDER);return shape;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){stopDiscovery();syncRetryHandler.removeCallbacks(syncRetry);if(watchConnection!=null)watchConnection.removeObserver(connectionObserver);io.shutdownNow();super.onDestroy();}
}
