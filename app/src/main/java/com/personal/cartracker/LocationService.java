package com.personal.cartracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public class LocationService extends Service {
    private static final long MOVING_INTERVAL_MS = 2_000L;
    private static final long SLOW_INTERVAL_MS = 5_000L;
    private static final long IDLE_INTERVAL_MS = 30_000L;
    private static final float MIN_DISTANCE_METERS = 3f;
    private static final float MAX_ALLOWED_ACCURACY_METERS = 35f;
    private static final float MAX_MPS = 80f;
    private static final long MAX_POINT_AGE_MS = 20_000L;
    private static final String PREFS = "tracker_state";
    private static final String PREF_SEQ = "sequence";

    private FusedLocationProviderClient fusedClient;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final List<Map<String, Object>> pendingUploads = new ArrayList<>();

    private LocationCallback locationCallback;
    private Location lastRawLocation;
    private Location lastAcceptedLocation;
    private MotionState motionState = MotionState.STARTING;
    private long sequence;
    private Kalman2D kalman;

    private enum MotionState {
        IDLE,
        STARTING,
        DRIVING,
        STOPPED,
        POOR_SIGNAL
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sequence = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(PREF_SEQ, 0L);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        kalman = new Kalman2D();
        startForeground(1, notification());
        startTrackingWithState(MotionState.STARTING);
    }

    private void startTrackingWithState(MotionState state) {
        motionState = state;
        if (!hasLocationPermission()) {
            return;
        }

        if (locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }

        LocationRequest request = createRequestForState(state);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) {
                    return;
                }

                processLocation(loc);
            }
        };

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }

    private LocationRequest createRequestForState(MotionState state) {
        long interval;
        switch (state) {
            case DRIVING:
                interval = MOVING_INTERVAL_MS;
                break;
            case STOPPED:
                interval = IDLE_INTERVAL_MS;
                break;
            case POOR_SIGNAL:
                interval = SLOW_INTERVAL_MS;
                break;
            case IDLE:
                interval = IDLE_INTERVAL_MS;
                break;
            case STARTING:
            default:
                interval = SLOW_INTERVAL_MS;
                break;
        }

        return new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_METERS)
                .setMinUpdateIntervalMillis(Math.min(interval, 2_000L))
                .setWaitForAccurateLocation(true)
                .build();
    }

    private void processLocation(Location loc) {
        lastRawLocation = loc;

        if (!isLocationFresh(loc) || !passesAccuracyGate(loc) || !passesMovementSanity(loc)) {
            updateState(MotionState.POOR_SIGNAL);
            trackKpis(false, loc, null);
            return;
        }

        updateState(resolveStateFromMotion(loc));

        long now = System.currentTimeMillis();
        SmoothedPoint smoothed = kalman.update(loc.getLatitude(), loc.getLongitude(), now);

        Location mapped = maybeSnapToRoad(smoothed.latitude, smoothed.longitude);
        Map<String, Object> payload = buildPayload(loc, mapped, smoothed, now);

        pendingUploads.add(payload);
        flushPendingUploads();

        lastAcceptedLocation = new Location(loc);
        trackKpis(true, loc, mapped);
    }

    private boolean isLocationFresh(Location loc) {
        long ageMs = System.currentTimeMillis() - loc.getTime();
        return ageMs <= MAX_POINT_AGE_MS;
    }

    private boolean passesAccuracyGate(Location loc) {
        return loc.hasAccuracy() && loc.getAccuracy() <= MAX_ALLOWED_ACCURACY_METERS;
    }

    private boolean passesMovementSanity(Location loc) {
        if (lastAcceptedLocation == null) {
            return true;
        }

        float distance = loc.distanceTo(lastAcceptedLocation);
        long dtMs = Math.max(1L, loc.getTime() - lastAcceptedLocation.getTime());
        float computedSpeed = distance / (dtMs / 1000f);
        return computedSpeed <= MAX_MPS;
    }

    private MotionState resolveStateFromMotion(Location loc) {
        float speed = Math.max(loc.getSpeed(), lastAcceptedLocation == null ? 0f :
                loc.distanceTo(lastAcceptedLocation) / Math.max(1f, (loc.getTime() - lastAcceptedLocation.getTime()) / 1000f));

        if (speed >= 3f) {
            return MotionState.DRIVING;
        }
        if (speed <= 0.5f) {
            return MotionState.STOPPED;
        }
        return MotionState.STARTING;
    }

    private void updateState(MotionState next) {
        if (next != motionState) {
            startTrackingWithState(next);
        }
    }

    private Map<String, Object> buildPayload(
            Location raw,
            Location mapped,
            SmoothedPoint smoothed,
            long now
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("deviceId", TrackerConfig.DEVICE_ID);
        payload.put("seq", nextSequence());
        payload.put("uuid", UUID.randomUUID().toString());
        payload.put("ts", now);
        payload.put("state", motionState.name());

        payload.put("rawLat", raw.getLatitude());
        payload.put("rawLng", raw.getLongitude());
        payload.put("rawAccuracy", raw.getAccuracy());
        payload.put("rawSpeed", raw.getSpeed());
        payload.put("rawBearing", raw.getBearing());

        payload.put("filteredLat", smoothed.latitude);
        payload.put("filteredLng", smoothed.longitude);

        payload.put("lat", mapped.getLatitude());
        payload.put("lng", mapped.getLongitude());
        payload.put("speed", raw.getSpeed());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            payload.put("bearingAccuracy", raw.hasBearingAccuracy() ? raw.getBearingAccuracyDegrees() : null);
            payload.put("verticalAccuracy", raw.hasVerticalAccuracy() ? raw.getVerticalAccuracyMeters() : null);
        }
        payload.put("provider", raw.getProvider());
        payload.put("elapsedRealtimeNanos", raw.getElapsedRealtimeNanos());
        return payload;
    }

    private void flushPendingUploads() {
        if (pendingUploads.isEmpty()) {
            return;
        }

        List<Map<String, Object>> batchPayload = new ArrayList<>(pendingUploads);
        ioExecutor.execute(() -> {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            WriteBatch writeBatch = db.batch();

            for (Map<String, Object> payload : batchPayload) {
                String docId = payload.get("ts") + "_" + payload.get("seq") + "_" + payload.get("uuid");
                writeBatch.set(
                        db.collection("devices")
                                .document(TrackerConfig.DEVICE_ID)
                                .collection("history")
                                .document(date)
                                .collection("points")
                                .document(docId),
                        payload
                );
            }

            writeBatch.commit()
                    .addOnSuccessListener(unused -> pendingUploads.removeAll(batchPayload))
                    .addOnFailureListener(e -> {
                        // queued in memory for retry on next flush
                    });
        });
    }

    private Location maybeSnapToRoad(double lat, double lng) {
        String apiKey = BuildConfig.MAPS_ROADS_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return asLocation(lat, lng);
        }

        try {
            String endpoint = "https://roads.googleapis.com/v1/snapToRoads?path="
                    + lat + "," + lng
                    + "&interpolate=false&key=" + apiKey;
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return asLocation(lat, lng);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(out.toString());
            JSONArray points = json.optJSONArray("snappedPoints");
            if (points == null || points.length() == 0) {
                return asLocation(lat, lng);
            }

            JSONObject location = points.getJSONObject(0).optJSONObject("location");
            if (location == null) {
                return asLocation(lat, lng);
            }

            return asLocation(location.optDouble("latitude", lat), location.optDouble("longitude", lng));
        } catch (Exception ignored) {
            return asLocation(lat, lng);
        }
    }

    private Location asLocation(double lat, double lng) {
        Location location = new Location("processed");
        location.setLatitude(lat);
        location.setLongitude(lng);
        return location;
    }

    private void trackKpis(boolean accepted, Location raw, @Nullable Location mapped) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("ts", System.currentTimeMillis());
        payload.put("accepted", accepted);
        payload.put("state", motionState.name());
        payload.put("rawAccuracy", raw.hasAccuracy() ? raw.getAccuracy() : null);
        payload.put("speed", raw.getSpeed());
        if (accepted && mapped != null) {
            payload.put("mapDeviationMeters", (float) raw.distanceTo(mapped));
        }

        db.collection("devices")
                .document(TrackerConfig.DEVICE_ID)
                .collection("metrics")
                .add(payload);
    }

    private long nextSequence() {
        sequence += 1;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putLong(PREF_SEQ, sequence).apply();
        return sequence;
    }

    private Notification notification() {
        String channelId = "tracker";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Car Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        return new Notification.Builder(this, channelId)
                .setContentTitle("Car tracking active - " + TrackerConfig.DEVICE_ID)
                .setContentText("State: " + motionState.name())
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static class SmoothedPoint {
        final double latitude;
        final double longitude;

        SmoothedPoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static class Kalman2D {
        private double lat;
        private double lng;
        private double velLat;
        private double velLng;
        private long lastTs;
        private boolean initialized;

        SmoothedPoint update(double measurementLat, double measurementLng, long ts) {
            if (!initialized) {
                lat = measurementLat;
                lng = measurementLng;
                lastTs = ts;
                initialized = true;
                return new SmoothedPoint(lat, lng);
            }

            double dt = Math.max(0.001, (ts - lastTs) / 1000.0);
            lastTs = ts;

            lat += velLat * dt;
            lng += velLng * dt;

            double gainPos = 0.2;
            double gainVel = 0.05;

            double residualLat = measurementLat - lat;
            double residualLng = measurementLng - lng;

            lat += gainPos * residualLat;
            lng += gainPos * residualLng;
            velLat += (gainVel * residualLat) / dt;
            velLng += (gainVel * residualLng) / dt;

            return new SmoothedPoint(lat, lng);
        }
    }
}
