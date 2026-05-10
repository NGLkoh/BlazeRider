package com.aorv.blazerider

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class RideReminderWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val rideId = inputData.getString("rideId") ?: return Result.failure()
        val rideTitle = inputData.getString("rideTitle") ?: "Your ride"

        return try {
            val db = FirebaseFirestore.getInstance()
            
            // Fetch the ride to get joined riders
            val rideDoc = db.collection("sharedRoutes").document(rideId).get().await()
            
            if (!rideDoc.exists()) return Result.success()
            
            val status = rideDoc.getString("status")
            if (status == "cancelled" || status == "completed" || status == "expired") {
                Log.d("RideReminderWorker", "Ride $rideId is $status, skipping reminders")
                return Result.success()
            }

            val joinedRidersMap = rideDoc.get("joinedRiders") as? Map<String, Any> ?: emptyMap()
            val joinedRiders = joinedRidersMap.keys.toList()
            val creatorId = rideDoc.getString("userUid")

            val recipients = (joinedRiders + creatorId).filterNotNull().distinct()

            if (recipients.isEmpty()) {
                Log.d("RideReminderWorker", "No recipients for ride $rideId")
                return Result.success()
            }

            val batch = db.batch()
            val now = Timestamp.now()

            recipients.forEach { userId ->
                val notificationRef = db.collection("users")
                    .document(userId)
                    .collection("notifications")
                    .document()

                val notification = mapOf(
                    "type" to "ride_reminder",
                    "message" to "Your ride '$rideTitle' starts in 20 minutes!",
                    "entityId" to rideId,
                    "entityType" to "ride",
                    "createdAt" to now,
                    "isRead" to false
                )
                batch.set(notificationRef, notification)
            }

            batch.commit().await()
            
            Log.d("RideReminderWorker", "Sent reminders for ride $rideId to ${recipients.size} users")
            Result.success()
        } catch (e: Exception) {
            Log.e("RideReminderWorker", "Error sending ride reminders", e)
            Result.retry()
        }
    }
}
