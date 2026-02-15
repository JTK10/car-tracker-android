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

public class MainActivity extends ComponentActivity {
    private ListenerRegistration controlListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show simple status UI
        setContentView(R.layout.activity_main);

        // Android 13+ notification permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        1001
                );
                return;
            }
        }

        // Permission already granted
        startTrackingService();

        setupControlListener();
    }

    private void startTrackingService() {
        startService(new Intent(this, LocationService.class));
    }

    private void setupControlListener() {
        controlListener = FirebaseFirestore.getInstance()
                .collection("devices")
                .document("car_001")
                .collection("control")
                .document("state")
                .addSnapshotListener((snapshot, e) -> {

                    if (snapshot != null && snapshot.exists()) {
                        Boolean start = snapshot.getBoolean("startTracking");

                        if (Boolean.TRUE.equals(start)) {
                            Intent intent = new Intent(this, LocationService.class);
                            startService(intent);

                            snapshot.getReference().update("startTracking", false);
                        }
                    }
                });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
