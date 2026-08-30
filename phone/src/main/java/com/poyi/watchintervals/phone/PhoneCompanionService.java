package com.poyi.watchintervals.phone;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;

/** Foreground owner that lets the paired BLE link reconnect outside the activity lifecycle. */
public final class PhoneCompanionService extends Service {
    private static final String CHANNEL="watch_companion";
    private final android.os.Handler syncHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService projectionExecutor=
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean projectionDrainInFlight=
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean destroyed=
            new java.util.concurrent.atomic.AtomicBoolean();
    private CloudV3Channel cloudChannel;
    private WatchConnectionManager watchConnection;
    private com.poyi.watchintervals.phone.connection.ConnectionState lastObservedState;
    private final WatchConnectionManager.Observer projectionObserver=snapshot->{
        drainPlanOutboxAsync();
        boolean becameReady=PhoneSyncPolicy.shouldAutoSync(lastObservedState,snapshot.state,false);
        lastObservedState=snapshot.state;
        if(becameReady) PhoneSleepSyncWorker.schedule(this);
    };
    private final Runnable statusUpload=new Runnable(){@Override public void run(){CloudV3Sync.syncLiveAsync(PhoneCompanionService.this);drainPlanOutboxAsync();syncHandler.postDelayed(this,CloudV3Sync.lastActiveSession(PhoneCompanionService.this)?10_000L:60_000L);}};
    @Override public void onCreate(){super.onCreate();NotificationChannel channel=new NotificationChannel(CHANNEL,"手表蓝牙连接",NotificationManager.IMPORTANCE_MIN);getSystemService(NotificationManager.class).createNotificationChannel(channel);Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("步序手表连接").setContentText("正在保持蓝牙与云端命令通道").setOngoing(true).build();startForeground(64,notification);watchConnection=WatchConnectionManager.get(this);watchConnection.observe(projectionObserver);watchConnection.connect();cloudChannel=new CloudV3Channel(this);cloudChannel.start();PhonePlanProjectionWorker.schedule(this);PhoneSleepSyncWorker.ensurePeriodic(this);syncHandler.post(statusUpload);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){WatchConnectionManager.get(this).connect();if(cloudChannel!=null)cloudChannel.start();EncryptedWatchSyncWorker.schedule(this);PhonePlanProjectionWorker.schedule(this);return START_STICKY;}
    private void drainPlanOutboxAsync(){
        if(destroyed.get())return;
        WatchConnectionManager connection=watchConnection==null?WatchConnectionManager.get(this):watchConnection;
        if(!PhonePlanProjectionSync.shouldAttempt(connection.snapshot().state,
                PhoneSyncOutbox.size(this),projectionDrainInFlight.get()))return;
        if(!projectionDrainInFlight.compareAndSet(false,true))return;
        try{projectionExecutor.execute(()->{try{
            if(!PhonePlanProjectionSync.drainOnce(this))PhonePlanProjectionWorker.schedule(this);
        }finally{projectionDrainInFlight.set(false);}});}catch(java.util.concurrent.RejectedExecutionException stopped){
            projectionDrainInFlight.set(false);if(!destroyed.get())PhonePlanProjectionWorker.schedule(this);
        }
    }
    @Override public void onDestroy(){destroyed.set(true);syncHandler.removeCallbacks(statusUpload);if(watchConnection!=null)watchConnection.removeObserver(projectionObserver);projectionExecutor.shutdownNow();if(cloudChannel!=null)cloudChannel.stop();PhoneBootReceiver.schedule(this);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
