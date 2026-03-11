package com.personal.cartracker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends ComponentActivity {
    private static final int REQ_PERMISSIONS = 1001;

    private ListenerRegistration controlListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (hasAllRequiredPermissions()) {
            startTrackingService();
            setupControlListener();
            return;
        }

        requestRequiredPermissions();
    }

    private boolean hasAllRequiredPermissions() {
        List<String> required = buildRequiredPermissionList();
        for (String permission : required) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestRequiredPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : buildRequiredPermissionList()) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    missing.toArray(new String[0]),
                    REQ_PERMISSIONS
            );
        }
    }

    private List<String> buildRequiredPermissionList() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= 29) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        return permissions;
    }

    private void startTrackingService() {
        Intent intent = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void setupControlListener() {
        controlListener = FirebaseFirestore.getInstance()
                .collection("devices")
                .document(TrackerConfig.DEVICE_ID)
                .collection("control")
                .document("state")
                .addSnapshotListener((snapshot, e) -> {
                    if (snapshot == null || !snapshot.exists()) {
                        return;
                    }

                    Boolean start = snapshot.getBoolean("startTracking");
                    if (!Boolean.TRUE.equals(start)) {
                        return;
                    }

                    Intent intent = new Intent(this, LocationService.class);
                    ContextCompat.startForegroundService(this, intent);

                    snapshot.getReference().update("startTracking", false);
                });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_PERMISSIONS) {
            return;
        }

        if (hasAllRequiredPermissions()) {
            startTrackingService();
            setupControlListener();
        }
    }

    @Override
    protected void onDestroy() {
        if (controlListener != null) {
            controlListener.remove();
            controlListener = null;
        }
        super.onDestroy();
    }
}
