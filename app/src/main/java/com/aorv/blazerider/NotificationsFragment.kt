package com.aorv.blazerider

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotificationsAdapter
    private lateinit var noNotificationsText: TextView
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val notifications = mutableListOf<Notification>()

    companion object {
        fun newInstance(): NotificationsFragment {
            return NotificationsFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_notifications, container, false)

        recyclerView = view.findViewById(R.id.notifications_recycler_view)
        noNotificationsText = view.findViewById(R.id.no_notifications_text)
        val markAllReadButton = view.findViewById<Button>(R.id.mark_all_read_button)
        val clearAllButton = view.findViewById<Button>(R.id.clear_all_button)

        adapter = NotificationsAdapter(notifications) { notification ->
            markAsRead(notification)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        loadNotifications()

        markAllReadButton.setOnClickListener {
            markAllAsRead()
        }

        clearAllButton.setOnClickListener {
            clearAllNotifications()
        }

        return view
    }

    private fun loadNotifications() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            showError("User not logged in")
            return
        }

        db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    showError("Failed to load notifications: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    notifications.clear()
                    for (doc in snapshot.documents) {
                        val notification = doc.toObject(Notification::class.java)?.copy(documentId = doc.id)
                        if (notification != null) {
                            notifications.add(notification)
                        }
                    }
                    updateUI()
                }
            }
    }

    private fun markAsRead(notification: Notification) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            showError("User not logged in")
            return
        }

        if (!notification.isRead) {
            db.collection("users").document(userId)
                .collection("notifications").document(notification.documentId)
                .update("isRead", true)
                .addOnSuccessListener {
                    val index = notifications.indexOfFirst { it.documentId == notification.documentId }
                    if (index != -1) {
                        notifications[index] = notification.copy(isRead = true)
                        adapter.notifyItemChanged(index)
                    }
                }
                .addOnFailureListener { e ->
                    showError("Failed to mark notification as read: ${e.message}")
                }
        }
    }

    private fun markAllAsRead() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            showError("User not logged in")
            return
        }

        val batch = db.batch()
        notifications.forEach { notification ->
            if (!notification.isRead) {
                val docRef = db.collection("users").document(userId)
                    .collection("notifications").document(notification.documentId)
                batch.update(docRef, "isRead", true)
            }
        }

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(context, "All notifications marked as read", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                showError("Failed to mark notifications as read: ${e.message}")
            }
    }

    private fun clearAllNotifications() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            showError("User not logged in")
            return
        }

        val batch = db.batch()
        notifications.forEach { notification ->
            val docRef = db.collection("users").document(userId)
                .collection("notifications").document(notification.documentId)
            batch.delete(docRef)
        }

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(context, "All notifications cleared", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                showError("Failed to clear notifications: ${e.message}")
            }
    }

    private fun updateUI() {
        if (notifications.isEmpty()) {
            recyclerView.visibility = View.GONE
            noNotificationsText.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noNotificationsText.visibility = View.GONE
            adapter.updateNotifications(notifications)
        }
    }

    private fun showError(message: String) {
        Log.e("NotificationsFragment", message)
        if (isAdded) { // Ensure fragment is attached before showing toast
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
