package com.personal.cartracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.FirebaseFirestore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocationService extends Service {
    private static final String DEVICE_ID = "car_001";
    private static final long INTERVAL_MS = 2 * 60 * 1000L;

    private FusedLocationProviderClient fusedClient;
    private Location lastLocation;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        startForeground(1, notification());
        startTracking();
    }

    private void startTracking() {
        LocationRequest request = new LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            INTERVAL_MS
        ).build();

        fusedClient.requestLocationUpdates(
            request,
            new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    Location loc = result.getLastLocation();
                    if (loc == null) {
                        return;
                    }
                    if (lastLocation == null || loc.distanceTo(lastLocation) > 30) {
                        saveLocation(loc);
                        lastLocation = loc;
                    }
                }
            },
            Looper.getMainLooper()
        );
    }

    private void saveLocation(Location loc) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        Map<String, Object> payload = new HashMap<>();
        payload.put("lat", loc.getLatitude());
        payload.put("lng", loc.getLongitude());
        payload.put("speed", loc.getSpeed());
        payload.put("ts", System.currentTimeMillis());

        db.collection("devices")
            .document(DEVICE_ID)
            .collection("history")
            .document(date)
            .collection("points")
            .document(time)
            .set(payload);
    }

    private Notification notification() {
        String channelId = "tracker";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                channelId,
                "Car Tracking",
                NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        return new Notification.Builder(this, channelId)
            .setContentTitle("Car tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
