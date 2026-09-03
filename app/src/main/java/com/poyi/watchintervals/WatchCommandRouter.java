package com.poyi.watchintervals;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared business router used by LAN and authenticated Bluetooth transports. */
final class WatchCommandRouter implements AutoCloseable {
    static final class Result { final int status;final String body;Result(int status,String body){this.status=status;this.body=body;} }
    @FunctionalInterface interface CheckedOperation { void run() throws Exception; }
    @FunctionalInterface interface CheckedResult<T> { T run() throws Exception; }
    private static final Object COMMAND_JOURNAL_LOCK = new Object();
    private final Context context;
    private final SystemSleepBridge sleep;
    private final SystemHealthBridge health;

    WatchCommandRouter(Context context){this.context=context.getApplicationContext();sleep=new SystemSleepBridge(this.context);health=new SystemHealthBridge(this.context);}

    Result route(String method,String path,String body){
        try {
            if("GET".equals(method)&&"/v1/status".equals(path))return ok(status());
            if("GET".equals(method)&&"/v1/plan".equals(path))return ok(PlanStore.encode(PlanStore.load(context)));
            if("GET".equals(method)&&"/v1/plan/profile".equals(path)){JSONObject selected=PlanLibraryStore.selectedPlanFrom(PlanLibraryStore.load(context));return ok(new JSONObject().put("id",selected==null?"":selected.optString("id")).put("name",PlanStore.name(context)).put("group",PlanStore.group(context)).put("requirement",PlanStore.requirement(context)).put("stages",new JSONArray(PlanStore.encode(PlanStore.load(context)))).toString());}
            if("GET".equals(method)&&"/v1/plan-library".equals(path))return ok(PlanLibraryStore.load(context).toString());
            if("PUT".equals(method)&&"/v1/plan-library".equals(path)){JSONObject library=PlanLibraryStore.replace(context,new JSONObject(body));return ok(new JSONObject().put("saved",true).put("revision",library.optLong("revision")).put("planCount",library.getJSONArray("plans").length()).put("selectedPlanId",library.optString("selectedPlanId")).toString());}
            if("POST".equals(method)&&"/v1/sync/operations".equals(path))return ok(applySyncOperations(new JSONObject(body)).toString());
            if("PUT".equals(method)&&"/v1/plan-selection".equals(path)){JSONObject selected=PlanLibraryStore.select(context,new JSONObject(body).optString("planId"));return ok(new JSONObject().put("selected",true).put("planId",selected.optString("id")).put("name",selected.optString("name")).toString());}
            if("PUT".equals(method)&&"/v1/plan".equals(path)){java.util.ArrayList<Stage> stages=PlanStore.decode(body);if(stages.isEmpty())return error(422,"invalid_plan");PlanStore.save(context,stages);return ok(new JSONObject().put("saved",true).put("stageCount",stages.size()).toString());}
            if("PUT".equals(method)&&"/v1/plan/profile".equals(path)){JSONObject profile=new JSONObject(body);java.util.ArrayList<Stage> stages=PlanStore.decode(profile.optJSONArray("stages")==null?null:profile.optJSONArray("stages").toString());if(stages.isEmpty())return error(422,"invalid_plan");PlanStore.saveProfile(context,profile.optString("name","自定义计划"),profile.optString("group","我的计划"),profile.optString("requirement","按阶段顺序完成训练。"),stages);return ok(new JSONObject().put("saved",true).put("stageCount",stages.size()).toString());}
            if("GET".equals(method)&&"/v1/history".equals(path))return ok(HistoryStore.toJson(context).toString());
            if("GET".equals(method)&&("/v1/sleep".equals(path)||path.startsWith("/v1/sleep?")))return ok(sleep.read(queryDays(path),queryInt(path,"offsetDays",0,0,365)).toString());
            if("GET".equals(method)&&("/v1/health".equals(path)||path.startsWith("/v1/health?")))return ok(health.read(queryDays(path)).toString());
            if("POST".equals(method)&&"/v1/health/insert".equals(path))return healthInsert(body);
            if("GET".equals(method)&&path.startsWith("/v1/history/")&&path.contains("/route")){String id=historyId(path,"/route");return ok(HistoryStore.routePage(context,id,queryInt(path,"cursor",0,0,Integer.MAX_VALUE),queryInt(path,"limit",500,1,1000)).toString());}
            if("GET".equals(method)&&path.startsWith("/v1/history/")&&path.contains("/heart")){String id=historyId(path,"/heart");return ok(HistoryStore.heartPage(context,id,queryInt(path,"cursor",0,0,Integer.MAX_VALUE),queryInt(path,"limit",500,1,1000)).toString());}
            if("GET".equals(method)&&path.startsWith("/v1/history/")){WorkoutRecord record=HistoryStore.find(context,path.substring("/v1/history/".length()));return record==null?error(404,"workout_not_found"):ok(record.toJson().toString());}
            if("DELETE".equals(method)&&path.startsWith("/v1/history/")){HistoryStore.delete(context,path.substring("/v1/history/".length()));return ok("{\"deleted\":true}");}
            if("POST".equals(method)&&"/v1/location".equals(path))return location(body);
            if("POST".equals(method)&&path.startsWith("/v1/control/"))return control(path.substring("/v1/control/".length()),body);
            return error(404,"not_found");
        } catch(Exception error){return error(500,"internal_error");}
    }

    private String status()throws Exception{JSONObject value=new JSONObject().put("device","OWW221").put("appVersion",BuildConfig.VERSION_NAME).put("deviceId",WatchDeviceIdentity.id(context)).put("protocolVersion",2).put("activeSession",WorkoutService.hasRecoverableSession(context)).put("sessionState",WorkoutService.persistedSessionState(context)).put("planState",WorkoutService.persistedPlanState(context)).put("backgroundLocation",context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED).put("transport","multi").put("bleSecurity",WatchLinkService.diagnostics()).put("port",WatchBridgeService.PORT);
        // Live pace/HR/cadence block while a workout runs, so phone and MCP callers see the
        // session itself rather than a bare activeSession flag.
        JSONObject workout=WorkoutService.liveWorkoutJson();if(workout!=null)value.put("workout",workout);
        return value.toString();}
    private Result location(String body)throws Exception{JSONObject point=new JSONObject(body);double latitude=point.optDouble("latitude",Double.NaN),longitude=point.optDouble("longitude",Double.NaN);if(!Double.isFinite(latitude)||!Double.isFinite(longitude))return error(422,"invalid_location");Intent relay=new Intent(context,WorkoutService.class).setAction(WorkoutService.ACTION_EXTERNAL_LOCATION).putExtra(WorkoutService.EXTRA_LATITUDE,latitude).putExtra(WorkoutService.EXTRA_LONGITUDE,longitude).putExtra(WorkoutService.EXTRA_ACCURACY,(float)point.optDouble("accuracy",30d)).putExtra(WorkoutService.EXTRA_SPEED,(float)point.optDouble("speed",-1d));context.startService(relay);return ok("{\"accepted\":true}");}
    private Result control(String action,String body)throws Exception{
        synchronized(COMMAND_JOURNAL_LOCK){return controlLocked(action,body);}
    }
    private Result controlLocked(String action,String body)throws Exception{
        JSONObject command=body==null||body.isEmpty()?new JSONObject():new JSONObject(body);
        String commandId=command.optString("commandId",java.util.UUID.randomUUID().toString());
        String signature=commandSignature(action,command);
        JSONObject journal=commandJournal(),entry=journal.optJSONObject(commandId);
        if(entry!=null){
            // Pre-journal builds stored the result directly. Never repeat their side effect; the
            // missing signature only means a different-body reuse cannot be distinguished.
            if(!entry.has("signature"))return result(new JSONObject(entry.toString()).put("duplicate",true));
            if(!signature.equals(entry.optString("signature")))return error(409,"command_id_reused");
            JSONObject cachedResult=entry.optJSONObject("result");
            if(cachedResult!=null)return result(new JSONObject(cachedResult.toString()).put("duplicate",true));
            return executePrepared(journal,commandId,entry,true);
        }
        long expiresAt=command.optLong("expiresAt",Long.MAX_VALUE);
        if(expiresAt<=System.currentTimeMillis())return finalResult(journal,commandId,signature,
                new JSONObject().put("accepted",false).put("commandId",commandId)
                        .put("error","command_expired").put("httpStatus",409));
        String expected=command.optString("expectedState","").toUpperCase(Locale.US);
        String actual=WorkoutService.persistedSessionState(context);
        if(!expected.isEmpty()&&!expected.equals(actual))return finalResult(journal,commandId,signature,
                new JSONObject().put("accepted",false).put("commandId",commandId)
                        .put("error","state_mismatch").put("actualState",actual).put("httpStatus",409));
        String resolvedAction=resolveExplicitAction(action,actual);
        if(resolvedAction.isEmpty())return error(422,"invalid_action");
        String resolvedPlanId="";
        if("start".equals(resolvedAction)){
            try{resolvedPlanId=resolveStartPlanId(PlanLibraryStore.load(context),command.optString("planId"));}
            catch(IllegalArgumentException unavailable){return error(409,"plan_unavailable");}
            if(resolvedPlanId.isEmpty())return error(409,"plan_unavailable");
        }
        if("delete_workout".equals(resolvedAction)){
            String workoutId=command.optString("workoutId");
            if(workoutId.isEmpty()||!workoutId.matches("[A-Za-z0-9._-]{1,200}"))return error(422,"invalid_workout_id");
        }
        entry=new JSONObject().put("signature",signature).put("requestedAction",action)
                .put("resolvedAction",resolvedAction).put("previousState",actual)
                .put("workoutId",command.optString("workoutId"));
        if(!resolvedPlanId.isEmpty())entry.put("resolvedPlanId",resolvedPlanId);
        journal.put(commandId,entry);
        trimJournal(journal,commandId);
        JSONObject preparedEntry=entry;
        return commitBeforeSideEffect(() -> saveCommandJournal(journal),
                () -> executePrepared(journal,commandId,preparedEntry,false));
    }
    private Result executePrepared(JSONObject journal,String commandId,JSONObject entry,
                                   boolean duplicate)throws Exception{
        String resolvedAction=entry.optString("resolvedAction"),resolvedPlanId=entry.optString("resolvedPlanId");
        JSONObject value=new JSONObject().put("accepted",true).put("commandId",commandId)
                .put("action",entry.optString("requestedAction",resolvedAction))
                .put("resolvedAction",resolvedAction)
                .put("previousState",entry.optString("previousState"));
        if("delete_workout".equals(resolvedAction)){
            boolean deleted=HistoryStore.delete(context,entry.optString("workoutId"));
            value.put("deleted",deleted).put("alreadyAbsent",!deleted);
        }else{
            Intent intent=new Intent(context,WorkoutService.class);
            if("start".equals(resolvedAction)){
                JSONObject selected=PlanLibraryStore.select(context,resolvedPlanId);
                JSONArray stageJson=selected.optJSONArray("stages");
                java.util.ArrayList<Stage> stages=PlanStore.decode(
                        stageJson==null?null:stageJson.toString());
                if(stages.isEmpty())throw new IllegalStateException("prepared_plan_unavailable");
                intent.setAction(WorkoutService.ACTION_START).putExtra("plan",PlanStore.encode(stages));
                value.put("planId",resolvedPlanId);
            }else if("pause".equals(resolvedAction))intent.setAction(WorkoutService.ACTION_PAUSE);
            else if("resume".equals(resolvedAction))intent.setAction(WorkoutService.ACTION_RESUME);
            else if("stop".equals(resolvedAction))intent.setAction(WorkoutService.ACTION_STOP);
            else throw new IllegalStateException("invalid_prepared_action");
            context.startForegroundService(intent);
        }
        if(duplicate)value.put("duplicate",true);
        entry.put("result",new JSONObject(value.toString()));
        journal.put(commandId,entry);
        saveCommandJournal(journal); // Failure leaves the prepared entry for an explicit replay.
        return result(value);
    }
    private Result result(JSONObject value){int status=value.optInt("httpStatus",200);value.remove("httpStatus");return new Result(status,value.toString());}
    static String resolveExplicitAction(String action,String actualState){
        if("start".equals(action)||"pause".equals(action)||"resume".equals(action)
                ||"stop".equals(action)||"delete_workout".equals(action))return action;
        if(!"toggle".equals(action))return "";
        return "PAUSED".equals(actualState)?"resume":"RUNNING".equals(actualState)?"pause":"";
    }
    static String resolveStartPlanId(JSONObject library,String requestedPlanId)throws Exception{
        String requested=requestedPlanId==null?"":requestedPlanId;
        JSONObject candidate=new JSONObject(library.toString());
        if(!requested.isEmpty())candidate.put("selectedPlanId",requested);
        JSONObject selected=PlanLibraryStore.selectedPlanFrom(candidate);
        return selected==null?"":selected.optString("id");
    }
    static <T>T commitBeforeSideEffect(CheckedOperation commit,CheckedResult<T> sideEffect)
            throws Exception{commit.run();return sideEffect.run();}
    static String commandSignature(String action,JSONObject command){return action+"|"+
            command.optLong("expiresAt",Long.MAX_VALUE)+"|"+
            command.optString("expectedState","").toUpperCase(Locale.US)+"|"+
            command.optLong("controlRevision",0L)+"|"+command.optString("workoutId","")+"|"+
            command.optString("planId","");}
    private Result finalResult(JSONObject journal,String id,String signature,JSONObject value)throws Exception{
        journal.put(id,new JSONObject().put("signature",signature)
                .put("result",new JSONObject(value.toString())));
        trimJournal(journal,id);saveCommandJournal(journal);return result(value);
    }
    private JSONObject commandJournal(){try{return new JSONObject(context.getSharedPreferences("command_cache",Context.MODE_PRIVATE).getString("items","{}"));}catch(Exception corrupted){throw new IllegalStateException("command_journal_corrupt",corrupted);}}
    static void trimJournal(JSONObject journal,String protectedId){
        JSONArray names=journal.names();
        while(names!=null&&names.length()>100){
            String removable="";
            for(int index=0;index<names.length();index++){
                String candidate=names.optString(index);
                if(!protectedId.equals(candidate)){removable=candidate;break;}
            }
            if(removable.isEmpty())return;
            journal.remove(removable);names=journal.names();
        }
    }
    private void saveCommandJournal(JSONObject journal){if(!context.getSharedPreferences("command_cache",Context.MODE_PRIVATE).edit().putString("items",journal.toString()).commit())throw new IllegalStateException("command_journal_commit_failed");}
    private JSONObject applySyncOperations(JSONObject request)throws Exception{return PlanLibraryStore.applySyncOperations(context,request);}
    private String historyId(String path,String suffix){int start="/v1/history/".length(),end=path.indexOf(suffix,start);return path.substring(start,end);}
    private int queryInt(String path,String name,int fallback,int min,int max){int q=path.indexOf('?');if(q<0)return fallback;for(String item:path.substring(q+1).split("&")){String[] pair=item.split("=",2);if(pair.length==2&&name.equals(pair[0]))try{return Math.max(min,Math.min(max,Integer.parseInt(pair[1])));}catch(Exception ignored){return fallback;}}return fallback;}
    private int queryDays(String path){int marker=path.indexOf("days=");if(marker<0)return 7;int end=path.indexOf('&',marker);try{return Math.max(1,Math.min(31,Integer.parseInt(path.substring(marker+5,end<0?path.length():end))));}catch(Exception ignored){return 7;}}
    private Result ok(String body){return new Result(200,body);}
    private Result error(int status,String code){try{return new Result(status,new JSONObject().put("error",code).toString());}catch(Exception ignored){return new Result(status,"{}");}}
    @Override public void close(){sleep.close();health.close();}

    private Result healthInsert(String body) throws Exception {
        JSONObject json = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
        long now = System.currentTimeMillis() / 1000L;
        long duration = json.optLong("duration", 1800L);
        long endTime = json.optLong("endTime", now);
        long startTime = json.optLong("startTime", endTime - duration);
        double calories = json.optDouble("calories", 150.0);
        int avgHr = json.optInt("avgHeartRate", 125);
        int maxHr = json.optInt("maxHeartRate", 155);
        int exerciseType = json.optInt("exerciseType", 0x2714);
        String pkg = json.optString("packageName", context.getPackageName());
        JSONObject res = health.insertExerciseSession(startTime, endTime, duration, calories, avgHr, maxHr, exerciseType, pkg);
        return ok(res.toString());
    }
}
