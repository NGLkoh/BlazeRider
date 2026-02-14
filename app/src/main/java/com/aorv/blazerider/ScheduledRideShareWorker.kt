package com.aorv.blazerider

import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import java.util.Date

class ScheduledRideShareWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val firestore = FirebaseFirestore.getInstance()
    private val CHANNEL_ID = "ride_share_notifications"

    override suspend fun doWork(): Result {
        val rideId = inputData.getString("rideId") ?: return Result.failure()
        val userId = inputData.getString("userId") ?: return Result.failure()

        return try {
            val myRideRef = firestore.collection("rides").document(rideId)
            val myRideDoc = myRideRef.get().await()

            if (!myRideDoc.exists()) {
                Log.e("ScheduledRideShareWorker", "My Ride document not found: $rideId")
                return Result.failure()
            }

            val myRideData = myRideDoc.data
            if (myRideData == null || !(myRideData["isScheduled"] as? Boolean ?: false)) {
                Log.d("ScheduledRideShareWorker", "Ride $rideId is no longer scheduled or already shared.")
                return Result.success() // Already handled or no longer scheduled
            }

            val sharedRouteRef = firestore.collection("sharedRoutes").document()

            // Handle rideTimestamp as Long or Timestamp
            val rideTimestampRaw = myRideDoc.get("rideTimestamp")
            val rideTimestamp = when (rideTimestampRaw) {
                is Long -> Timestamp(Date(rideTimestampRaw))
                is Timestamp -> rideTimestampRaw
                else -> Timestamp.now()
            }

            val sharedRideData = hashMapOf(
                "datetime" to rideTimestamp, // Use the original ride datetime
                "createdAt" to Timestamp.now(), // This is the actual publish time
                "destination" to myRideDoc.getString("endLocationName"),
                "destinationCoordinates" to mapOf(
                    "latitude" to (myRideDoc.getDouble("endLat") ?: 0.0),
                    "longitude" to (myRideDoc.getDouble("endLng") ?: 0.0)
                ),
                "distance" to (myRideDoc.getDouble("distance") ?: 0.0),
                "duration" to (myRideDoc.getLong("duration")?.toDouble() ?: 0.0),
                "origin" to myRideDoc.getString("startLocationName"),
                "originCoordinates" to mapOf(
                    "latitude" to (myRideDoc.getDouble("startLat") ?: 0.0),
                    "longitude" to (myRideDoc.getDouble("startLng") ?: 0.0)
                ),
                "userName" to (myRideDoc.getString("userName") ?: ""),
                "userUid" to userId,
                "isAdminEvent" to false,
                "isScheduled" to false, // Now it's published
                "status" to "active"
            )

            // Fetch user name for shared ride data and notification
            val userDoc = firestore.collection("users").document(userId).get().await()
            if (userDoc.exists()) {
                val firstName = userDoc.getString("firstName") ?: ""
                val lastName = userDoc.getString("lastName") ?: ""
                val fullName = "$firstName $lastName".trim()
                sharedRideData["userName"] = fullName
            }

            val batch = firestore.batch()
            batch.set(sharedRouteRef, sharedRideData)
            batch.update(myRideRef, "isScheduled", false, "originalSharedRouteId", sharedRouteRef.id)
            
            // Also update currentJoinedRide for the user so they can track it immediately if they want
            val userRef = firestore.collection("users").document(userId)
            batch.update(userRef, "currentJoinedRide", sharedRouteRef.id)
            
            batch.commit().await()

            sendRideSharedNotification(userId, sharedRideData["destination"] as? String ?: "your ride")

            Log.d("ScheduledRideShareWorker", "Ride $rideId automatically shared to sharedRoutes as ${sharedRouteRef.id}")
            Result.success()
        } catch (e: Exception) {
            Log.e("ScheduledRideShareWorker", "Error publishing scheduled ride: ${e.message}", e)
            Result.retry()
        }
    }

    private fun sendRideSharedNotification(targetUserId: String, rideDestination: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ride Share Notifications", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blaze_rider) // Use your app icon
            .setContentTitle("Ride Shared!")
            .setContentText("Your scheduled ride to $rideDestination has been shared.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(targetUserId.hashCode(), notification)

        // Also save to Firestore notifications for in-app visibility
        val notificationData = hashMapOf(
            "actorId" to null, // System-generated
            "createdAt" to FieldValue.serverTimestamp(),
            "message" to "Your scheduled ride to $rideDestination has been shared.",
            "type" to "ride shared",
            "isRead" to false
        )
        firestore.collection("users").document(targetUserId).collection("notifications").add(notificationData)
    }
}
