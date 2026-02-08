package com.personal.cartracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class LocationService : Service() {

    private val deviceId = "car_001"
    private val intervalMs = 2 * 60 * 1000L
    private lateinit var fusedClient: FusedLocationProviderClient
    private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        startForeground(1, notification())
        startTracking()
    }

    private fun startTracking() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).build()

        fusedClient.requestLocationUpdates(
            request,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    if (lastLocation == null || loc.distanceTo(lastLocation!!) > 30) {
                        saveLocation(loc)
                        lastLocation = loc
                    }
                }
            },
            Looper.getMainLooper()
        )
    }

    private fun saveLocation(loc: Location) {
        val db = FirebaseFirestore.getInstance()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

        db.collection("devices")
            .document(deviceId)
            .collection("history")
            .document(date)
            .collection("points")
            .document(time)
            .set(
                mapOf(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "speed" to loc.speed,
                    "ts" to System.currentTimeMillis()
                )
            )
    }

    private fun notification(): Notification {
        val channelId = "tracker"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                channelId,
                "Car Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }

        return Notification.Builder(this, channelId)
            .setContentTitle("Car tracking active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

