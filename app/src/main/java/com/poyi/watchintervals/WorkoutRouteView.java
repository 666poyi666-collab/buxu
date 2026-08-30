package com.poyi.watchintervals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.MapStatusUpdate;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.map.Polyline;
import com.baidu.mapapi.map.PolylineOptions;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;
import com.baidu.mapapi.utils.CoordinateConverter;
import java.util.ArrayList;

/**
 * Stock-style Baidu vector map for workout trails.
 *
 * <p>The route remains lightweight until its pager page settles. While visible, one polyline and
 * two markers are reused; camera fitting is throttled so GPS refreshes never fight page motion.</p>
 */
final class WorkoutRouteView extends FrameLayout {
    private static final long CAMERA_REFIT_INTERVAL_MILLIS = 5_000L;
    private static final float MAX_ROUTE_ZOOM = 19f;
    private static final float SINGLE_POINT_ZOOM = 18f;
    private static final float CAMERA_HORIZONTAL_PADDING_DP = 15f;
    private static final float CAMERA_VERTICAL_PADDING_DP = 25f;

    private final TextView empty;
    private final ArrayList<LatLng> points = new ArrayList<>();
    private MapView mapView;
    private BaiduMap map;
    private Polyline route;
    private Marker startMarker;
    private Marker endMarker;
    private BitmapDescriptor startIcon;
    private BitmapDescriptor endIcon;
    private boolean active = true;
    private boolean attached;
    private boolean mapResumed;
    private boolean mapDestroyed;
    private int renderedCount = -1;
    private double renderedLastLatitude = Double.NaN;
    private double renderedLastLongitude = Double.NaN;
    private long lastCameraRefitElapsed;
    private int renderGeneration;

    WorkoutRouteView(Context context) {
        super(context);
        setBackground(Ui.background(context, Color.rgb(18, 22, 23), Ui.RADIUS_ROUTE));
        setClipToOutline(true);

        empty = new TextView(context);
        empty.setText(BaiduMapRuntime.isConfigured()
                ? "等待有效定位轨迹" : "地图授权待配置");
        empty.setTextColor(Ui.MUTED);
        empty.setTextSize(12);
        empty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(Ui.background(context, Color.argb(230, 31, 35, 36), Ui.RADIUS_CARD));
        FrameLayout.LayoutParams emptyParams =
                new FrameLayout.LayoutParams(-1, Ui.dp(context, 58), Gravity.CENTER);
        emptyParams.leftMargin = Ui.dp(context, 28);
        emptyParams.rightMargin = Ui.dp(context, 28);
        addView(empty, emptyParams);
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        if (!active) {
            renderGeneration++;
            pauseMap();
            return;
        }
        if (!points.isEmpty() && ensureMap()) {
            resumeMap();
            applyRouteToMap(true);
        }
    }

    boolean isActive() {
        return active;
    }

    void setEmptyMessage(String message) {
        if (!BaiduMapRuntime.isConfigured()) {
            empty.setText("地图授权待配置");
            return;
        }
        empty.setText(message == null || message.trim().isEmpty()
                ? "等待有效定位轨迹" : message);
    }

    private boolean ensureMap() {
        if (mapView != null && !mapDestroyed) return true;
        if (!BaiduMapRuntime.initialize(getContext())) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("地图授权待配置");
            return false;
        }
        if (mapView != null) removeView(mapView);
        mapDestroyed = false;
        mapView = new MapView(getContext());
        String stylePath = BaiduMapRuntime.installDarkStyle(getContext());
        if (stylePath != null) {
            mapView.setMapCustomStylePath(stylePath);
            mapView.setMapCustomStyleEnable(true);
        }
        mapView.showZoomControls(false);
        mapView.showScaleControl(false);
        mapView.setOnTouchListener((view, event) -> true);
        map = mapView.getMap();
        map.setMapType(BaiduMap.MAP_TYPE_NORMAL);
        map.setMapBackgroundColor(Color.rgb(14, 16, 18));
        map.setBuildingsEnabled(false);
        map.setIndoorEnable(false);
        map.setTrafficEnabled(false);
        map.showMapIndoorPoi(false);
        map.getUiSettings().setAllGesturesEnabled(false);
        map.getUiSettings().setCompassEnabled(false);
        map.setMaxAndMinZoomLevel(21f, 4f);
        map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(
                convertGps(39.915, 116.404), 14f));

        startIcon = BitmapDescriptorFactory.fromBitmap(markerBitmap(Ui.RED, false));
        endIcon = BitmapDescriptorFactory.fromBitmap(markerBitmap(Color.WHITE, true));
        addView(mapView, 0, new FrameLayout.LayoutParams(-1, -1));
        applyRouteToMap(true);
        resumeMap();
        return true;
    }

    void setRoute(double[] latitudes, double[] longitudes) {
        int count = Math.min(
                latitudes == null ? 0 : latitudes.length,
                longitudes == null ? 0 : longitudes.length);
        double lastLat = count == 0 ? Double.NaN : latitudes[count - 1];
        double lastLon = count == 0 ? Double.NaN : longitudes[count - 1];
        if (count == renderedCount
                && Double.compare(lastLat, renderedLastLatitude) == 0
                && Double.compare(lastLon, renderedLastLongitude) == 0) return;

        boolean canAppend = count > renderedCount
                && renderedCount > 0
                && Double.compare(latitudes[renderedCount - 1], renderedLastLatitude) == 0
                && Double.compare(longitudes[renderedCount - 1], renderedLastLongitude) == 0;
        if (!canAppend) {
            points.clear();
            appendPoints(latitudes, longitudes, 0, count);
        } else {
            appendPoints(latitudes, longitudes, renderedCount, count);
        }

        renderedCount = count;
        renderedLastLatitude = lastLat;
        renderedLastLongitude = lastLon;
        if (points.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            if (route != null) {
                route.setVisible(false);
                startMarker.setVisible(false);
                endMarker.setVisible(false);
            }
            return;
        }

        if (!BaiduMapRuntime.isConfigured()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("地图授权待配置");
            return;
        }
        empty.setVisibility(View.GONE);
        if (!active || !ensureMap()) return;
        applyRouteToMap(!canAppend);
    }

    private void appendPoints(double[] latitudes, double[] longitudes, int from, int to) {
        for (int index = Math.max(0, from); index < to; index++) {
            double latitude = latitudes[index];
            double longitude = longitudes[index];
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) continue;
            points.add(convertGps(latitude, longitude));
        }
    }

    private static LatLng convertGps(double latitude, double longitude) {
        LatLng source = new LatLng(latitude, longitude);
        LatLng converted = new CoordinateConverter()
                .from(CoordinateConverter.CoordType.GPS)
                .coord(source)
                .convert();
        return converted == null ? source : converted;
    }

    private void applyRouteToMap(boolean forceCamera) {
        if (map == null || points.isEmpty()) return;
        if (route == null) {
            route = (Polyline) map.addOverlay(new PolylineOptions()
                    .points(points)
                    .color(Ui.LIME)
                    .width(Ui.dp(getContext(), 3))
                    .lineCapType(PolylineOptions.LineCapType.LineCapRound)
                    .lineJoinType(PolylineOptions.LineJoinType.LineJoinRound)
                    .clickable(false));
            startMarker = (Marker) map.addOverlay(new MarkerOptions()
                    .position(points.get(0))
                    .icon(startIcon)
                    .anchor(0.5f, 0.5f)
                    .clickable(false));
            endMarker = (Marker) map.addOverlay(new MarkerOptions()
                    .position(points.get(points.size() - 1))
                    .icon(endIcon)
                    .anchor(0.5f, 0.5f)
                    .clickable(false));
        } else {
            route.setPoints(points);
            route.setVisible(true);
            startMarker.setPosition(points.get(0));
            startMarker.setVisible(true);
            endMarker.setPosition(points.get(points.size() - 1));
            endMarker.setVisible(true);
        }

        long now = SystemClock.elapsedRealtime();
        boolean refit = forceCamera || lastCameraRefitElapsed == 0
                || now - lastCameraRefitElapsed >= CAMERA_REFIT_INTERVAL_MILLIS;
        if (!refit) return;
        lastCameraRefitElapsed = now;
        int generation = ++renderGeneration;
        mapView.post(() -> {
            if (!active || map == null || generation != renderGeneration || points.isEmpty()) return;
            MapStatusUpdate camera;
            if (points.size() == 1) {
                camera = MapStatusUpdateFactory.newLatLngZoom(points.get(0), SINGLE_POINT_ZOOM);
            } else {
                LatLngBounds bounds = new LatLngBounds.Builder().include(points).build();
                int contentWidth = Math.max(1, mapView.getWidth()
                        - Ui.dp(getContext(), CAMERA_HORIZONTAL_PADDING_DP * 2));
                int contentHeight = Math.max(1, mapView.getHeight()
                        - Ui.dp(getContext(), CAMERA_VERTICAL_PADDING_DP * 2));
                camera = MapStatusUpdateFactory.newLatLngBounds(
                        bounds, contentWidth, contentHeight);
            }
            map.setMapStatus(camera);
            if (map.getMapStatus().zoom > MAX_ROUTE_ZOOM) {
                LatLng center = points.size() == 1
                        ? points.get(0) : new LatLngBounds.Builder().include(points).build().getCenter();
                map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(center, MAX_ROUTE_ZOOM));
            }
        });
    }

    private Bitmap markerBitmap(int color, boolean hollow) {
        int size = Ui.dp(getContext(), 17);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(hollow ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(Ui.dp(getContext(), 3));
        paint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f,
                size / 2f - Ui.dp(getContext(), 2), paint);
        if (!hollow) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 2));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(size / 2f, size / 2f,
                    size / 2f - Ui.dp(getContext(), 1), paint);
        }
        return bitmap;
    }

    private void resumeMap() {
        if (!active || !attached || mapView == null || mapResumed) return;
        mapView.onResume();
        mapResumed = true;
    }

    private void pauseMap() {
        if (mapView == null || !mapResumed) return;
        mapView.onPause();
        mapResumed = false;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (mapDestroyed && active && !points.isEmpty()) ensureMap();
        resumeMap();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        renderGeneration++;
        pauseMap();
        if (mapView != null && !mapDestroyed) {
            mapView.onDestroy();
            mapDestroyed = true;
            map = null;
            route = null;
            startMarker = null;
            endMarker = null;
            startIcon = null;
            endIcon = null;
        }
        super.onDetachedFromWindow();
    }
}
