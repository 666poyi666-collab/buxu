package com.poyi.watchintervals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
    private static volatile boolean coordinateConversionAvailable = true;

    private final TextView empty;
    private final VectorTrailCanvas vectorCanvas;
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
    private boolean mapUnavailable;
    private int renderedCount = -1;
    private double renderedLastLatitude = Double.NaN;
    private double renderedLastLongitude = Double.NaN;
    private long lastCameraRefitElapsed;
    private int renderGeneration;

    WorkoutRouteView(Context context) {
        super(context);
        setBackground(Ui.background(context, Color.rgb(18, 22, 23), Ui.RADIUS_ROUTE));
        setClipToOutline(true);

        vectorCanvas = new VectorTrailCanvas(context);
        vectorCanvas.setVisibility(View.GONE);
        addView(vectorCanvas, new FrameLayout.LayoutParams(-1, -1));

        empty = new TextView(context);
        FrameLayout.LayoutParams emptyParams =
                new FrameLayout.LayoutParams(-1, Ui.dp(context, 58), Gravity.CENTER);
        emptyParams.leftMargin = Ui.dp(context, 28);
        emptyParams.rightMargin = Ui.dp(context, 28);
        addView(empty, emptyParams);
        empty.setText("等待有效定位轨迹");
        empty.setTextColor(Ui.MUTED);
        empty.setTextSize(12);
        empty.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        empty.setGravity(Gravity.CENTER);
        empty.setBackground(Ui.background(context, Color.argb(230, 31, 35, 36), Ui.RADIUS_CARD));
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
        empty.setText(message == null || message.trim().isEmpty()
                ? "等待有效定位轨迹" : message);
    }

    private boolean ensureMap() {
        if (mapUnavailable) return false;
        try {
            return ensureMapUnchecked();
        } catch (LinkageError | RuntimeException error) {
            mapUnavailable = true;
            map = null;
            empty.setVisibility(View.VISIBLE);
            empty.setText("地图在当前设备不可用");
            android.util.Log.w("WorkoutRouteView", "Map runtime unavailable", error);
            return false;
        }
    }

    private boolean ensureMapUnchecked() {
        if (mapView != null && !mapDestroyed) return true;
        if (!BaiduMapRuntime.initialize(getContext())) {
            empty.setVisibility(points.isEmpty() ? View.VISIBLE : View.GONE);
            empty.setText("等待有效定位轨迹");
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

        if (!BaiduMapRuntime.isConfigured() || mapUnavailable) {
            empty.setVisibility(View.GONE);
            vectorCanvas.setVisibility(View.VISIBLE);
            vectorCanvas.setRoutePoints(points);
            return;
        }
        empty.setVisibility(View.GONE);
        if (!active || !ensureMap()) {
            vectorCanvas.setVisibility(View.VISIBLE);
            vectorCanvas.setRoutePoints(points);
            return;
        }
        vectorCanvas.setVisibility(View.GONE);
        applyRouteToMap(!canAppend);
    }

    private void appendPoints(double[] latitudes, double[] longitudes, int from, int to) {
        for (int index = Math.max(0, from); index < to; index++) {
            double latitude = latitudes[index];
            double longitude = longitudes[index];
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90d || latitude > 90d
                    || longitude < -180d || longitude > 180d) continue;
            points.add(convertGps(latitude, longitude));
        }
    }

    private static LatLng convertGps(double latitude, double longitude) {
        LatLng source = new LatLng(latitude, longitude);
        if (!coordinateConversionAvailable) return source;
        try {
            LatLng converted = new CoordinateConverter()
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(source)
                    .convert();
            return converted == null ? source : converted;
        } catch (LinkageError unavailable) {
            coordinateConversionAvailable = false;
            android.util.Log.w("WorkoutRouteView", "Coordinate conversion unavailable", unavailable);
            return source;
        } catch (RuntimeException invalidPoint) {
            return source;
        }
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

    private static final class VectorTrailCanvas extends View {
        private final ArrayList<LatLng> routePoints = new ArrayList<>();
        private final Paint bgGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        VectorTrailCanvas(Context context) {
            super(context);
            bgGridPaint.setColor(Color.argb(22, 255, 255, 255));
            bgGridPaint.setStyle(Paint.Style.STROKE);
            bgGridPaint.setStrokeWidth(Ui.dp(context, 1));

            linePaint.setColor(Ui.LIME);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(Ui.dp(context, 3.2f));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);

            glowPaint.setColor(Color.argb(55, 163, 230, 53));
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(Ui.dp(context, 6.5f));
            glowPaint.setStrokeCap(Paint.Cap.ROUND);
            glowPaint.setStrokeJoin(Paint.Join.ROUND);

            startPaint.setColor(Ui.CYAN);
            startPaint.setStyle(Paint.Style.FILL);

            endPaint.setColor(Ui.RED);
            endPaint.setStyle(Paint.Style.FILL);

            dotInnerPaint.setColor(Color.WHITE);
            dotInnerPaint.setStyle(Paint.Style.FILL);

            badgePaint.setColor(Ui.MUTED);
            badgePaint.setTextSize(Ui.textPixels(context, 11f));
            badgePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setRoutePoints(ArrayList<LatLng> pts) {
            routePoints.clear();
            if (pts != null) routePoints.addAll(pts);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            int gridStep = Ui.dp(getContext(), 26);
            for (int x = gridStep; x < w; x += gridStep) canvas.drawLine(x, 0, x, h, bgGridPaint);
            for (int y = gridStep; y < h; y += gridStep) canvas.drawLine(0, y, w, y, bgGridPaint);

            if (routePoints.isEmpty()) return;

            int size = routePoints.size();
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (int i = 0; i < size; i++) {
                LatLng p = routePoints.get(i);
                if (p.latitude < minLat) minLat = p.latitude;
                if (p.latitude > maxLat) maxLat = p.latitude;
                if (p.longitude < minLon) minLon = p.longitude;
                if (p.longitude > maxLon) maxLon = p.longitude;
            }

            float pad = Ui.dp(getContext(), 20);
            float availW = Math.max(1, w - pad * 2);
            float availH = Math.max(1, h - pad * 2);

            double meanLat = (minLat + maxLat) / 2d;
            double cosLat = Math.cos(Math.toRadians(meanLat));
            double latSpan = Math.max(0.0001, maxLat - minLat);
            double lonSpan = Math.max(0.0001, (maxLon - minLon) * Math.max(0.2, cosLat));

            float scaleX = (float) (availW / lonSpan);
            float scaleY = (float) (availH / latSpan);
            float scale = Math.min(scaleX, scaleY);

            float cx = pad + (availW - (float) (lonSpan * scale)) / 2f;
            float cy = pad + (availH - (float) (latSpan * scale)) / 2f;

            path.reset();
            float startX = 0, startY = 0, endX = 0, endY = 0;

            for (int i = 0; i < size; i++) {
                LatLng p = routePoints.get(i);
                float x = (float) (cx + (p.longitude - minLon) * cosLat * scale);
                float y = (float) (cy + (maxLat - p.latitude) * scale);
                if (i == 0) {
                    path.moveTo(x, y);
                    startX = x; startY = y;
                } else {
                    path.lineTo(x, y);
                }
                if (i == size - 1) {
                    endX = x; endY = y;
                }
            }

            if (size > 1) {
                canvas.drawPath(path, glowPaint);
                canvas.drawPath(path, linePaint);
            }

            float rOuter = Ui.dp(getContext(), 5.5f);
            float rInner = Ui.dp(getContext(), 2.2f);
            canvas.drawCircle(startX, startY, rOuter, startPaint);
            canvas.drawCircle(startX, startY, rInner, dotInnerPaint);

            if (size > 1) {
                canvas.drawCircle(endX, endY, rOuter, endPaint);
                canvas.drawCircle(endX, endY, rInner, dotInnerPaint);
            }

            canvas.drawText("GPS 矢量轨迹 · " + size + " 点", pad * 0.6f, pad * 0.8f, badgePaint);
        }
    }
}
