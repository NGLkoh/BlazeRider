package com.aorv.blazerider

import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.google.firebase.firestore.FieldValue
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

class ScheduledRideShareWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val firestore = FirebaseFirestore.getInstance()
    private val CHANNEL_ID = "ride_share_notifications"

    override suspend fun doWork(): Result {
        val rideId = inputData.getString("rideId") ?: return Result.failure()
        val userId = inputData.getString("userId") ?: return Result.failure()
        val sharedRouteId = inputData.getString("sharedRouteId") ?: return Result.failure()

        return try {
            val myRideRef = firestore.collection("rides").document(rideId)
            val sharedRouteRef = firestore.collection("sharedRoutes").document(sharedRouteId)

            val myRideDoc = myRideRef.get().await()
            if (!myRideDoc.exists()) {
                Log.e("ScheduledRideShareWorker", "My Ride document not found: $rideId")
                return Result.failure()
            }

            val myRideData = myRideDoc.data
            if (myRideData == null || !(myRideData["isScheduled"] as? Boolean ?: false)) {
                Log.d("ScheduledRideShareWorker", "Ride $rideId is no longer scheduled or already shared.")
                return Result.success()
            }

            // The sharedRoute document was already created by LocationFragment.
            // We just need to mark it as "published" (no longer scheduled for the future).
            // This is mostly for UI badges or notification purposes now.
            
            val batch = firestore.batch()
            batch.update(myRideRef, "isScheduled", false)
            batch.update(sharedRouteRef, "isScheduled", false)
            
            batch.commit().await()

            sendRideSharedNotification(userId, myRideData["endLocationName"] as? String ?: "your ride")

            Log.d("ScheduledRideShareWorker", "Scheduled ride $rideId marked as active (sharedRoute: $sharedRouteId)")
            Result.success()
        } catch (e: Exception) {
            Log.e("ScheduledRideShareWorker", "Error updating scheduled ride: ${e.message}", e)
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
            .setSmallIcon(R.drawable.ic_blaze_rider)
            .setContentTitle("Ride Shared!")
            .setContentText("Your scheduled ride to $rideDestination is now live and joinable.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(targetUserId.hashCode(), notification)

        val notificationData = hashMapOf(
            "actorId" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "message" to "Your scheduled ride to $rideDestination is now live and joinable.",
            "type" to "ride shared",
            "isRead" to false
        )
        firestore.collection("users").document(targetUserId).collection("notifications").add(notificationData)
    }
}
