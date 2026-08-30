package com.poyi.watchintervals.phone;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Phone history detail with a real slippy-map base layer and system-sports style metrics. */
public class HistoryDetailActivity extends Activity {
    private MapView map;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Palette.BG);
        getWindow().setNavigationBarColor(Palette.NAV);
        getWindow().setNavigationBarDividerColor(Palette.NAV);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                );
        Configuration.getInstance().load(this,getSharedPreferences("osmdroid",MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        JSONObject record;
        try { record=new JSONObject(getIntent().getStringExtra("record")); } catch(Exception error) { finish(); return; }

        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(Palette.BG);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(28)); scroll.addView(root);
        LinearLayout back=new LinearLayout(this);back.setGravity(Gravity.CENTER_VERTICAL);back.setClickable(true);back.setFocusable(true);back.setContentDescription("返回运动记录");
        PhoneSymbolView backIcon=new PhoneSymbolView(this,PhoneSymbol.BACK);backIcon.setTint(Palette.MOVE);back.addView(backIcon,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView backLabel=text("运动记录",24,true,Palette.TEXT);LinearLayout.LayoutParams backLabelParams=new LinearLayout.LayoutParams(-2,-1);backLabelParams.leftMargin=dp(4);back.addView(backLabel,backLabelParams);
        back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,dp(54)));
        root.addView(text(new SimpleDateFormat("yyyy年MM月dd日  HH:mm",Locale.CHINA).format(new Date(record.optLong("startedAt"))),14,false,Palette.TEXT_DIM));

        FrameLayout mapShell=new FrameLayout(this); mapShell.setBackground(round(Palette.CARD,8));mapShell.setClipToOutline(true);
        map=new MapView(this); map.setTileSource(new AmapTileSource()); map.setMinZoomLevel(4d); map.setMaxZoomLevel(18d); map.setMultiTouchControls(true); map.setTilesScaledToDpi(true); map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
        mapShell.addView(map,new FrameLayout.LayoutParams(-1,-1));
        JSONArray route=record.optJSONArray("route"); ArrayList<GeoPoint> points=points(route);
        if(points.isEmpty()){
            map.setVisibility(android.view.View.GONE);
            LinearLayout empty=new LinearLayout(this);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);
            PhoneSymbolView location=new PhoneSymbolView(this,PhoneSymbol.LOCATION);location.setTint(Palette.TEXT_DIM);empty.addView(location,new LinearLayout.LayoutParams(dp(30),dp(30)));
            TextView emptyLabel=text("本次训练未记录定位轨迹",15,true,Palette.TEXT_DIM);emptyLabel.setGravity(Gravity.CENTER);LinearLayout.LayoutParams emptyLabelParams=new LinearLayout.LayoutParams(-2,-2);emptyLabelParams.topMargin=dp(8);empty.addView(emptyLabel,emptyLabelParams);
            mapShell.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        }else{
            Polyline line=new Polyline();line.setPoints(points);line.getOutlinePaint().setColor(Color.rgb(139,221,48));line.getOutlinePaint().setStrokeWidth(dp(6));map.getOverlays().add(line);
            Marker start=marker(points.get(0),"起点",Color.rgb(35,190,205)); Marker end=marker(points.get(points.size()-1),"终点",Color.rgb(139,221,48));map.getOverlays().add(start);map.getOverlays().add(end);
            if(points.size()==1){map.getController().setZoom(17d);map.getController().setCenter(points.get(0));}
            else {BoundingBox box=BoundingBox.fromGeoPoints(points); map.post(()->map.zoomToBoundingBox(box,true,dp(52)));}
        }
        LinearLayout.LayoutParams mapParams=new LinearLayout.LayoutParams(-1,dp(points.isEmpty()?136:340));mapParams.topMargin=dp(16);root.addView(mapShell,mapParams);
        if(!points.isEmpty()){TextView attribution=text("高德地图  ·  绿色为运动轨迹",11,false,Palette.TEXT_DIM);attribution.setGravity(Gravity.CENTER);root.addView(attribution,new LinearLayout.LayoutParams(-1,dp(38)));}

        double meters=record.optDouble("distanceMeters");long duration=record.optLong("durationMs");int steps=record.optInt("steps"),heart=record.optInt("averageHeartRate");
        LinearLayout summary=card();summary.addView(text("运动概览",17,true,Palette.TEXT));LinearLayout summaryRow=new LinearLayout(this);summaryRow.addView(metricCell("总距离",PhoneFormat.distance(meters),true),weight());summaryRow.addView(metricCell("运动时间",PhoneFormat.duration(duration),false),weight());summaryRow.addView(metricCell("平均配速",PhoneFormat.pace(duration,meters),false),weight());summary.addView(summaryRow);root.addView(summary,marginTop(8));
        LinearLayout detail=card();detail.addView(text("详细数据",17,true,Palette.TEXT));LinearLayout detailRow=new LinearLayout(this);detailRow.addView(metricCell("平均心率",heart>0?heart+" bpm":"--",false),weight());detailRow.addView(metricCell("步数",steps+" 步",false),weight());LinearLayout detailRow2=new LinearLayout(this);detailRow2.addView(metricCell("平均步频",duration>0&&steps>0?Math.round(steps/(duration/60000d))+" 步/分":"--",false),weight());detailRow2.addView(metricCell("轨迹点",record.optInt("routePointCount",points.size())+" 个",false),weight());detail.addView(detailRow);detail.addView(detailRow2);root.addView(detail,marginTop(14));
        LinearLayout timing=card();timing.addView(text("时间与速度",17,true,Palette.TEXT));long elapsed=record.optLong("elapsedDurationMs",duration),paused=record.optLong("pausedDurationMs");timing.addView(dataLine("活动时间",PhoneFormat.duration(duration)));timing.addView(dataLine("总历时",PhoneFormat.duration(elapsed)));timing.addView(dataLine("暂停时间",PhoneFormat.duration(paused)));timing.addView(dataLine("平均时速",meters>0&&duration>0?String.format(Locale.CHINA,"%.1f km/h",meters/duration*3600d):"--"));timing.addView(dataLine("最高平滑时速",record.optDouble("maxSmoothedSpeedMps")>0?String.format(Locale.CHINA,"%.1f km/h",record.optDouble("maxSmoothedSpeedMps")*3.6d):"--"));if(record.optLong("planCompletedActiveMs")>0){timing.addView(dataLine("计划完成",PhoneFormat.duration(record.optLong("planCompletedActiveMs"))));timing.addView(dataLine("自由记录",PhoneFormat.duration(record.optLong("freeRecordingActiveMs"))));}root.addView(timing,marginTop(14));
        JSONObject sources=record.optJSONObject("distanceBySourceMeters");if(sources!=null){LinearLayout sourceCard=card();sourceCard.addView(text("距离来源",17,true,Palette.TEXT));String[][] labels={{"system_exercise","系统运动"},{"watch_gps","手表 GPS"},{"phone_gps","手机 GPS"},{"steps_estimate","步数估距"}};for(String[] item:labels){double value=sources.optDouble(item[0]);if(value>0)sourceCard.addView(dataLine(item[1],PhoneFormat.distance(value)));}root.addView(sourceCard,marginTop(14));}
        if(record.has("bestPaceSecondsPerKm")||record.has("elevationGainMeters")){LinearLayout performance=card();performance.addView(text("运动表现",17,true,Palette.TEXT));if(record.has("bestPaceSecondsPerKm"))performance.addView(dataLine("最佳配速",PhoneFormat.paceSeconds(record.optLong("bestPaceSecondsPerKm"))));if(record.has("elevationGainMeters"))performance.addView(dataLine("累计爬升",Math.round(record.optDouble("elevationGainMeters"))+" 米"));root.addView(performance,marginTop(14));}
        JSONArray splits=record.optJSONArray("splits");if(splits!=null&&splits.length()>0){LinearLayout splitCard=card();splitCard.addView(text("公里分段",17,true,Palette.TEXT));for(int i=0;i<splits.length();i++){JSONObject split=splits.optJSONObject(i);if(split!=null)splitCard.addView(dataLine(split.optInt("index")+" 公里",PhoneFormat.duration(split.optLong("durationMs"))+"  ·  "+PhoneFormat.paceSeconds(split.optLong("paceSecondsPerKm"))));}root.addView(splitCard,marginTop(14));}
        JSONArray stageResults=record.optJSONArray("stageResults");if(stageResults!=null&&stageResults.length()>0){LinearLayout stageCard=card();stageCard.addView(text("训练阶段",17,true,Palette.TEXT));for(int i=0;i<stageResults.length();i++){JSONObject stage=stageResults.optJSONObject(i);if(stage!=null)stageCard.addView(dataLine(stage.optInt("index")+"  "+stage.optString("name"),PhoneFormat.duration(stage.optLong("completedAtMs"))));}root.addView(stageCard,marginTop(14));}
        setContentView(scroll);
    }

    private ArrayList<GeoPoint> points(JSONArray route){ArrayList<GeoPoint> result=new ArrayList<>();if(route!=null)for(int i=0;i<route.length();i++){Object raw=route.opt(i);double lat=Double.NaN,lon=Double.NaN;if(raw instanceof JSONArray){JSONArray p=(JSONArray)raw;if(p.length()>=2){lat=p.optDouble(0,Double.NaN);lon=p.optDouble(1,Double.NaN);}}else if(raw instanceof JSONObject){JSONObject p=(JSONObject)raw;lat=p.optDouble("latitude",Double.NaN);lon=p.optDouble("longitude",Double.NaN);}if(Double.isFinite(lat)&&Double.isFinite(lon))result.add(AmapTileSource.fromWgs84(lat,lon));}return result;}
    private LinearLayout dataLine(String label,String value){LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(9),0,dp(9));TextView left=text(label,14,false,Palette.TEXT);TextView right=text(value,14,true,Palette.TEXT);right.setGravity(Gravity.END);row.addView(left,new LinearLayout.LayoutParams(0,-2,1f));row.addView(right,new LinearLayout.LayoutParams(-2,-2));return row;}
    private Marker marker(GeoPoint point,String title,int color){Marker marker=new Marker(map);marker.setPosition(point);marker.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER);marker.setTitle(title);GradientDrawable icon=new GradientDrawable();icon.setShape(GradientDrawable.OVAL);icon.setColor(color);icon.setStroke(dp(3),Color.WHITE);icon.setSize(dp(20),dp(20));marker.setIcon(icon);return marker;}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable background=round(Palette.CARD,8);background.setStroke(dp(1),Palette.BORDER);v.setBackground(background);v.setElevation(0);return v;}
    private TextView metricCell(String label,String value,boolean hero){TextView v=text(label+"\n"+value,hero?18:16,true,Palette.TEXT);v.setGravity(Gravity.CENTER);v.setLineSpacing(dp(4),1f);v.setPadding(dp(3),dp(13),dp(3),dp(13));return v;}
    private TextView text(String value,int size,boolean bold,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout.LayoutParams marginTop(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1f);}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onResume(){super.onResume();if(map!=null)map.onResume();}
    @Override protected void onPause(){if(map!=null)map.onPause();super.onPause();}
}
