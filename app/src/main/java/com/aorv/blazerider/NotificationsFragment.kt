package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

class NotificationsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotificationsAdapter
    private lateinit var noNotificationsText: TextView
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val displayNotifications = mutableListOf<DisplayNotification>()

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
        val clearAllButton = view.findViewById<Button>(R.id.clear_all_button)
        val backButton = view.findViewById<ImageView>(R.id.back_button)

        adapter = NotificationsAdapter(displayNotifications) { displayNotification, position ->
            handleNotificationClick(displayNotification, position)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        loadNotifications()

        clearAllButton.setOnClickListener {
            clearAllNotifications()
        }

        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        return view
    }

    private fun loadNotifications() {
        val userId = auth.currentUser?.uid ?: run {
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
                if (snapshot == null) return@addSnapshotListener

                // Ignore local metadata changes to prevent UI flickering
                if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

                val sourceNotifications = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(Notification::class.java)?.copy(documentId = doc.id)
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val newDisplayList = sourceNotifications.map { notification ->
                        async {
                            val title = if (notification.actorId != null) {
                                fetchUserName(notification.actorId)
                            } else {
                                if (notification.type == "weather") "Weather Update"
                                else notification.type.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                            }
                            DisplayNotification(
                                title = title,
                                message = notification.message,
                                isRead = notification.isRead,
                                original = notification
                            )
                        }
                    }.awaitAll()

                    displayNotifications.clear()
                    displayNotifications.addAll(newDisplayList)
                    updateUI()
                }
            }
    }

    private suspend fun fetchUserName(userId: String): String = withContext(Dispatchers.IO) {
        try {
            val userDoc = db.collection("users").document(userId).get().await()
            val firstName = userDoc.getString("firstName") ?: ""
            val lastName = userDoc.getString("lastName") ?: ""
            "$firstName $lastName".trim().ifEmpty { "Unknown User" }
        } catch (e: Exception) {
            "Unknown Sender"
        }
    }

    private fun handleNotificationClick(displayNotification: DisplayNotification, position: Int) {
        val userId = auth.currentUser?.uid ?: return
        val original = displayNotification.original

        // Mark as read in Firestore if not already read
        if (!original.isRead && original.documentId.isNotEmpty()) {
            // Optimistic UI update
            displayNotifications[position].isRead = true
            adapter.notifyItemChanged(position)
            
            db.collection("users").document(userId)
                .collection("notifications").document(original.documentId)
                .update("isRead", true)
                .addOnFailureListener { e ->
                    // Revert UI if update fails
                    displayNotifications[position].isRead = false
                    adapter.notifyItemChanged(position)
                    showError("Failed to update notification status")
                }
        }

        // Redirect based on notification type
        when (original.type) {
            "reaction" -> {
                original.entityId?.let { postId ->
                    val intent = Intent(requireContext(), SinglePostActivity::class.java).apply {
                        putExtra("POST_ID", postId)
                    }
                    startActivity(intent)
                }
            }
            "comment" -> {
                original.entityId?.let { postId ->
                    val intent = Intent(requireContext(), CommentsActivity::class.java).apply {
                        putExtra("POST_ID", postId)
                    }
                    startActivity(intent)
                }
            }
            "message" -> {
                original.entityId?.let { chatId ->
                    val intent = Intent(requireContext(), ChatConversationActivity::class.java).apply {
                        putExtra("chatId", chatId)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun clearAllNotifications() {
        val userId = auth.currentUser?.uid ?: run {
            showError("User not logged in")
            return
        }
        if (displayNotifications.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Notifications")
            .setMessage("Are you sure you want to delete all notifications?")
            .setPositiveButton("Clear") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val batch = db.batch()
                        displayNotifications.forEach { displayNotif ->
                            val docRef = db.collection("users").document(userId)
                                .collection("notifications").document(displayNotif.original.documentId)
                            batch.delete(docRef)
                        }
                        batch.commit().await()
                        Toast.makeText(context, "Notifications cleared", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        showError("Failed to clear notifications: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateUI() {
        if (!isAdded) return

        if (displayNotifications.isEmpty()) {
            recyclerView.visibility = View.GONE
            noNotificationsText.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noNotificationsText.visibility = View.GONE
        }
        adapter.updateNotifications(displayNotifications)
    }

    private fun showError(message: String) {
        if (isAdded) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        Log.e("NotificationsFragment", message)
    }
}
