package com.aorv.blazerider

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class ScheduledPostWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val postId = inputData.getString("postId")
        val sharedRouteId = inputData.getString("sharedRouteId")

        if (postId == null && sharedRouteId == null) return Result.failure()

        return try {
            val db = FirebaseFirestore.getInstance()
            val batch = db.batch()
            
            // 1. Update the feed post if present
            postId?.let {
                val postRef = db.collection("posts").document(it)
                batch.update(postRef, "isScheduled", false)
            }
            
            // 2. Update the shared ride event if present
            sharedRouteId?.let {
                val sharedRef = db.collection("sharedRoutes").document(it)
                batch.update(sharedRef, "isScheduled", false)
            }

            batch.commit().await()

            Log.d("ScheduledPostWorker", "Published: Post=$postId, Ride=$sharedRouteId")
            
            Result.success()
        } catch (e: Exception) {
            Log.e("ScheduledPostWorker", "Error updating scheduled items", e)
            Result.retry()
        }
    }
}
