package com.aorv.blazerider

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CalendarView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EventsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var calendarView: CalendarView
    private lateinit var postAdapter: PostAdapter
    private val db = FirebaseFirestore.getInstance()

    private var selectedStartDate: Date? = null
    private var selectedEndDate: Date? = null

    private val postLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            // When PostActivity is successful, refresh the currently selected date
            if (selectedStartDate != null && selectedEndDate != null) {
                fetchPostsForDate(selectedStartDate!!, selectedEndDate!!)
            }
        }
    }

    private val commentsActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(CommentsActivity.COMMENT_ADDED, false) == true) {
            if (selectedStartDate != null && selectedEndDate != null) {
                fetchPostsForDate(selectedStartDate!!, selectedEndDate!!)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_events, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        calendarView = view.findViewById(R.id.calendar_view)
        recyclerView = view.findViewById(R.id.feed_recycler_view)
        val whatsOnMindButton = view.findViewById<View>(R.id.btn_whats_on_mind)
        val addImageButton = view.findViewById<View>(R.id.btn_add_image)

        postAdapter = PostAdapter(
            onDeletePost = { post -> deletePost(post) },
            onCommentClick = { post ->
                val intent = Intent(requireContext(), CommentsActivity::class.java).apply {
                    putExtra("POST_ID", post.id)
                }
                commentsActivityLauncher.launch(intent)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = postAdapter

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val calendar = Calendar.getInstance().apply {
                set(year, month, dayOfMonth, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            selectedStartDate = calendar.time

            calendar.set(year, month, dayOfMonth, 23, 59, 59)
            selectedEndDate = calendar.time

            fetchPostsForDate(selectedStartDate!!, selectedEndDate!!)
        }

        whatsOnMindButton.setOnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", true)
            }
            postLauncher.launch(intent)
        }

        addImageButton.setOnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", true)
            }
            postLauncher.launch(intent)
        }

        loadTodaysPosts()
    }

    private fun loadTodaysPosts() {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        selectedStartDate = todayStart

        val todayEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.time
        selectedEndDate = todayEnd

        fetchPostsForDate(todayStart, todayEnd)
    }

    private fun fetchPostsForDate(startDate: Date, endDate: Date) {
        db.collection("posts")
            .whereEqualTo("admin", true)
            .whereGreaterThanOrEqualTo("createdAt", startDate)
            .whereLessThanOrEqualTo("createdAt", endDate)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                if (!isAdded) return@addOnSuccessListener

                val fetchedPosts = result.documents.mapNotNull { document ->
                    try {
                        Post(
                            id = document.id,
                            userId = document.getString("userId") ?: "",
                            content = document.getString("content") ?: "",
                            createdAt = document.getTimestamp("createdAt")?.toDate(),
                            imageUris = document.get("imageUris") as? List<String> ?: emptyList(),
                            reactionCount = document.get("reactionCount") as? Map<String, Long> ?: emptyMap(),
                            commentsCount = document.getLong("commentsCount") ?: 0L,
                            admin = document.getBoolean("admin") ?: false
                        )
                    } catch (e: Exception) {
                        Log.e("EventsFragment", "Error parsing post ${document.id}", e)
                        null
                    }
                }
                postAdapter.submitPosts(fetchedPosts)
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(requireContext(), "Error fetching events: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deletePost(post: Post) {
        db.collection("posts").document(post.id)
            .delete()
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                Toast.makeText(requireContext(), "Event deleted successfully", Toast.LENGTH_SHORT).show()
                if (selectedStartDate != null && selectedEndDate != null) {
                    fetchPostsForDate(selectedStartDate!!, selectedEndDate!!)
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(requireContext(), "Error deleting event: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
