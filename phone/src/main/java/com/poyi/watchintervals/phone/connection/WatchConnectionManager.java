package com.poyi.watchintervals.phone.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.poyi.watchintervals.phone.EncryptedWatchSyncWorker;
import com.poyi.watchintervals.phone.connection.ble.BleGattTransport;
import com.poyi.watchintervals.phone.connection.lan.LanHttpTransport;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import org.json.JSONObject;

/** Process-wide owner of watch identity, reconnect policy, and transport selection. */
public final class WatchConnectionManager {
    public interface Observer { void onConnectionState(Snapshot snapshot); }
    public static final class Snapshot {
        public final ConnectionState state;public final TransportType primaryTransport,bulkTransport;public final String watchDeviceId,lastDisconnectReason;public final long lastSeenAt,lastSuccessfulRequestAt;public final int rssi,mtu,pendingOperations;public final boolean notificationsSubscribed,lanAvailable;
        Snapshot(ConnectionState state,TransportType primary,TransportType bulk,String watchId,String reason,long seen,long request,int rssi,int mtu,boolean subscribed,boolean lan,int pending){this.state=state;primaryTransport=primary;bulkTransport=bulk;watchDeviceId=watchId;lastDisconnectReason=reason;lastSeenAt=seen;lastSuccessfulRequestAt=request;this.rssi=rssi;this.mtu=mtu;notificationsSubscribed=subscribed;lanAvailable=lan;pendingOperations=pending;}
    }
    private static volatile WatchConnectionManager instance;
    public static WatchConnectionManager get(Context context){if(instance==null)synchronized(WatchConnectionManager.class){if(instance==null)instance=new WatchConnectionManager(context.getApplicationContext());}return instance;}

    private final Context context;private final WatchIdentityStore identity;private final BleGattTransport ble;private final LanHttpTransport lan=new LanHttpTransport();private final ConnectionBackoff backoff=new ConnectionBackoff();private final Handler main=new Handler(Looper.getMainLooper());private final List<Observer> observers=new CopyOnWriteArrayList<>();
    private volatile ConnectionState state=ConnectionState.IDLE;private volatile String reason="";private volatile long lastSeen,lastRequest;private volatile boolean lanVerified;private volatile int pendingOperations;private boolean reconnectScheduled;private CompletableFuture<TransportSession> connectAttempt;
    private final java.util.concurrent.atomic.AtomicBoolean lanVerifyInFlight=new java.util.concurrent.atomic.AtomicBoolean();

    private WatchConnectionManager(Context context){this.context=context;identity=new WatchIdentityStore(context);ble=new BleGattTransport(context,identity);ble.subscribe(event->{if(WatchCloudBridgeEvent.isHistoryChanged(event))main.post(()->EncryptedWatchSyncWorker.schedule(this.context));});ble.setStateListener((next,cause)->{ConnectionState previous=state;setState(next,cause);if(next==ConnectionState.DISCONNECTED&&!"requested".equals(cause)&&(previous==ConnectionState.CONNECTED_BLE||previous==ConnectionState.CONNECTED_BLE_LAN||previous==ConnectionState.SYNCING))scheduleReconnect();});restoreLan();}
    public WatchIdentityStore identity(){return identity;}
    public void configurePairing(String pairingCode){if(!identity.isPaired()&&pairingCode!=null&&!pairingCode.trim().isEmpty())identity.setPairingCode(pairingCode.trim());}
    public void configureLan(String host,String pairingCode){String credential=identity.lanCredential();String previous=lan.host();lan.configure(host,credential.isEmpty()?pairingCode:credential);if(!lan.isAvailable())return;if(!lan.host().equals(previous))context.getSharedPreferences("connection",Context.MODE_PRIVATE).edit().putString("host",lan.host()).apply();if(!lanVerified||!lan.host().equals(previous))verifyLan();}
    /** Re-arms the LAN transport from persisted pairing state so a cold process can serve MCP
     *  requests without first paying a full BLE connect timeout. */
    private void restoreLan(){String host=context.getSharedPreferences("connection",Context.MODE_PRIVATE).getString("host","").trim();if(!host.isEmpty()&&!identity.lanCredential().isEmpty())configureLan(host,"");}
    public void setPendingOperations(int value){pendingOperations=Math.max(0,value);publish();}
    public void observe(Observer observer){if(observer==null)return;observers.add(observer);main.post(()->observer.onConnectionState(snapshot()));}
    public void removeObserver(Observer observer){observers.remove(observer);}

    public synchronized CompletableFuture<TransportSession> connect(){return connect(false);}

    private synchronized CompletableFuture<TransportSession> connect(boolean forceBleRecovery){
        reconnectScheduled=false;
        if(!identity.isPaired()&&identity.pairingCode().length()!=6){
            setState(ConnectionState.UNPAIRED,"pairing_required");
            CompletableFuture<TransportSession> result=new CompletableFuture<>();
            result.completeExceptionally(new IllegalStateException("pairing_required"));
            return result;
        }
        if(ConnectionRecoveryPolicy.isBleReady(state)){
            return CompletableFuture.completedFuture(
                    new TransportSession(TransportType.BLE,identity.watchDeviceId(),ble.mtu()));
        }
        if(ConnectionRecoveryPolicy.mayReuseLan(state,lanVerified,forceBleRecovery)){
            return CompletableFuture.completedFuture(
                    new TransportSession(TransportType.LAN,identity.watchDeviceId(),0));
        }
        if(connectAttempt!=null&&!connectAttempt.isDone()) return connectAttempt;

        CompletableFuture<TransportSession> result=new CompletableFuture<>();
        connectAttempt=result;
        if(lan.isAvailable()&&!lanVerified)verifyLan();
        ble.connect().whenComplete((session,error)->{
            if(error==null){
                backoff.reset();
                lastSeen=System.currentTimeMillis();
                if(lan.isAvailable())lan.configure(lan.host(),identity.lanCredential());
                setState(ConnectionState.CONNECTED_BLE,null);
                result.complete(session);
                synchronized(WatchConnectionManager.this){if(connectAttempt==result)connectAttempt=null;}
                main.post(()->{EncryptedWatchSyncWorker.schedule(context);com.poyi.watchintervals.phone.PhonePlanProjectionWorker.schedule(context);});
                verifyLan();
            }else if(lan.isAvailable())lan.connect().whenComplete((lanSession,lanError)->{
                if(lanError==null){
                    lanVerified=true;lastSeen=System.currentTimeMillis();
                    setState(ConnectionState.CONNECTED_LAN,"ble_unavailable");
                    result.complete(lanSession);
                    synchronized(WatchConnectionManager.this){if(connectAttempt==result)connectAttempt=null;}
                    main.post(()->{EncryptedWatchSyncWorker.schedule(context);com.poyi.watchintervals.phone.PhonePlanProjectionWorker.schedule(context);});
                    scheduleReconnect();
                }else{result.completeExceptionally(lanError);synchronized(WatchConnectionManager.this){if(connectAttempt==result)connectAttempt=null;}scheduleReconnect();}
            });
            else{result.completeExceptionally(error);synchronized(WatchConnectionManager.this){if(connectAttempt==result)connectAttempt=null;}scheduleReconnect();}
        });
        return result;
    }
    public void connectNow(){backoff.reset();connect();}
    /** Probes the LAN transport on its own. BLE on this watch fails often enough (gatt_147) that
     *  LAN readiness must not depend on a successful BLE cycle first. */
    private void verifyLan(){if(!lan.isAvailable()){lanVerified=false;publish();return;}if(!lanVerifyInFlight.compareAndSet(false,true))return;lan.connect().whenComplete((session,error)->{lanVerifyInFlight.set(false);lanVerified=error==null;if(!lanVerified){publish();return;}lastSeen=System.currentTimeMillis();boolean bleUp=state==ConnectionState.CONNECTED_BLE||state==ConnectionState.CONNECTED_BLE_LAN;setState(bleUp?ConnectionState.CONNECTED_BLE_LAN:ConnectionState.CONNECTED_LAN,bleUp?null:"ble_unavailable");});}

    public CompletableFuture<ResponseEnvelope> request(String method,String path,String body,long ttlMillis){
        RequestEnvelope request=RequestEnvelope.create(method,path,body,ttlMillis);
        WatchTransport transport=select(path);
        CompletableFuture<ResponseEnvelope> pending;
        if(transport==null){
            pending=connect().thenCompose(ignored->{
                WatchTransport connectedTransport=select(path);
                if(connectedTransport==null){
                    CompletableFuture<ResponseEnvelope> failed=new CompletableFuture<>();
                    failed.completeExceptionally(new IllegalStateException("WATCH_OFFLINE"));
                    return failed;
                }
                return requestWithFallback(method,request,connectedTransport);
            });
        }else pending=requestWithFallback(method,request,transport);
        return pending.thenApply(response->{lastSeen=System.currentTimeMillis();lastRequest=lastSeen;publish();if(response.status>=400)throw new RuntimeException("WATCH_"+response.status+" "+response.body);return response;});
    }

    private CompletableFuture<ResponseEnvelope> requestWithFallback(String method,
            RequestEnvelope request, WatchTransport transport){
        CompletableFuture<ResponseEnvelope> first=transport.request(request);
        if(transport!=lan || !TransportFallbackPolicy.shouldRetryOnBle(method,
                TransportType.LAN,ble.isAvailable())) return first;
        CompletableFuture<ResponseEnvelope> result=new CompletableFuture<>();
        first.whenComplete((response,error)->{
            if(error==null){result.complete(response);return;}
            // A remembered LAN address can outlive the Wi-Fi session. Mark it degraded and
            // replay only idempotent reads after an authenticated BLE session is available.
            lanVerified=false;
            ConnectionState fallbackState=state;
            if(TransportFallbackPolicy.isBleSessionReady(fallbackState))
                setState(ConnectionState.CONNECTED_BLE,"lan_request_failed");
            else publish();
            CompletableFuture<TransportSession> ready=
                    TransportFallbackPolicy.isBleSessionReady(fallbackState)
                            ?CompletableFuture.completedFuture(null):ble.connect();
            ready.whenComplete((ignored,connectError)->{
                if(connectError!=null){result.completeExceptionally(error);return;}
                long retryTtl=TransportFallbackPolicy.remainingTtl(request.expiresAt,
                        System.currentTimeMillis());
                if(request.expiresAt>0L&&retryTtl<=0L){
                    result.completeExceptionally(error);return;
                }
                RequestEnvelope retry=RequestEnvelope.create(request.method,request.path,
                        request.body,retryTtl);
                ble.request(retry).whenComplete((bleResponse,bleError)->{
                    if(bleError==null)result.complete(bleResponse);
                    else result.completeExceptionally(error);
                });
            });
        });
        return result;
    }
    public String requestBlocking(String method,String path,String body,long ttlMillis)throws Exception{return request(method,path,body,ttlMillis).get(Math.max(5_000L,ttlMillis<=0?20_000L:ttlMillis+2_000L),TimeUnit.MILLISECONDS).body;}
    private WatchTransport select(String path){boolean control=path.equals("/v1/status")||path.startsWith("/v1/control/")||path.equals("/v1/location")||path.equals("/v1/sync/operations")||path.startsWith("/v1/plan");if(control&&ble.isAvailable()&&(state==ConnectionState.CONNECTED_BLE||state==ConnectionState.CONNECTED_BLE_LAN||state==ConnectionState.SYNCING))return ble;if(!control&&lanVerified&&lan.isAvailable())return lan;if(ble.isAvailable()&&(state==ConnectionState.CONNECTED_BLE||state==ConnectionState.CONNECTED_BLE_LAN||state==ConnectionState.DEGRADED_BLE))return ble;if(lanVerified&&lan.isAvailable())return lan;return null;}
    public synchronized void disconnect(){reconnectScheduled=false;CompletableFuture<TransportSession> pending=connectAttempt;connectAttempt=null;if(pending!=null&&!pending.isDone())pending.completeExceptionally(new IllegalStateException("disconnected"));ble.disconnect();lan.disconnect();lanVerified=false;setState(ConnectionState.DISCONNECTED,"requested");}
    private synchronized void scheduleReconnect(){if(reconnectScheduled||!identity.isPaired()&&identity.pairingCode().length()!=6)return;reconnectScheduled=true;long delay=backoff.nextDelayMillis();if(ConnectionRecoveryPolicy.shouldExposeBackoff(lanVerified))setState(ConnectionState.BACKOFF,"retry_in_"+delay);else{reason="ble_retry_in_"+delay;persist();publish();}main.postDelayed(()->{synchronized(WatchConnectionManager.this){reconnectScheduled=false;}connect(true);},delay);}
    private void setState(ConnectionState next,String cause){state=next;reason=cause==null?"":cause;persist();publish();}
    private void persist(){context.getSharedPreferences("watch_connection_state",Context.MODE_PRIVATE).edit().putString("state",state.name()).putString("transport",state==ConnectionState.CONNECTED_BLE_LAN?"ble_lan":state==ConnectionState.CONNECTED_BLE?"ble":"none").putString("watchDeviceId",identity.watchDeviceId()).putLong("lastSeenAt",lastSeen).putLong("lastSuccessfulRequestAt",lastRequest).putString("lastDisconnectReason",reason).putInt("rssi",ble.rssi()).putBoolean("lanAvailable",lanVerified).putInt("pendingOperations",pendingOperations).apply();}
    private void publish(){Snapshot value=snapshot();for(Observer observer:observers)main.post(()->observer.onConnectionState(value));}
    public Snapshot snapshot(){TransportType primary=(state==ConnectionState.CONNECTED_BLE||state==ConnectionState.CONNECTED_BLE_LAN||state==ConnectionState.DEGRADED_BLE)?TransportType.BLE:state==ConnectionState.CONNECTED_LAN?TransportType.LAN:null;TransportType bulk=lanVerified?TransportType.LAN:primary;return new Snapshot(state,primary,bulk,identity.watchDeviceId(),reason,lastSeen,lastRequest,ble.rssi(),ble.mtu(),ble.notificationsSubscribed(),lanVerified,pendingOperations);}
    public JSONObject diagnostics(){Snapshot value=snapshot();try{return new JSONObject().put("connectionState",value.state.name()).put("primaryTransport",value.primaryTransport==null?JSONObject.NULL:value.primaryTransport.name().toLowerCase(Locale.ROOT)).put("bulkTransport",value.bulkTransport==null?JSONObject.NULL:value.bulkTransport.name().toLowerCase(Locale.ROOT)).put("watchDeviceId",value.watchDeviceId).put("rssi",value.rssi).put("mtu",value.mtu).put("notificationsSubscribed",value.notificationsSubscribed).put("lastSeenAt",value.lastSeenAt).put("lastSuccessfulRequestAt",value.lastSuccessfulRequestAt).put("lastDisconnectReason",value.lastDisconnectReason).put("lanAvailable",value.lanAvailable).put("pendingOperations",value.pendingOperations);}catch(Exception ignored){return new JSONObject();}}
    public boolean replayLastBleMessageForDiagnostics(){return ble.replayLastSecureMessageForDiagnostics();}
}
