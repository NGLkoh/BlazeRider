package com.aorv.blazerider

import android.app.Activity
import android.app.TimePickerDialog
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
import java.util.Calendar
import java.util.Date

class EventsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var calendarView: CalendarView
    private lateinit var postAdapter: PostAdapter
    private val db = FirebaseFirestore.getInstance()

    // These hold the range for querying posts to display in the list
    private var queryStartDate: Date? = null
    private var queryEndDate: Date? = null

    // This holds the timestamp user selected (starts as "now")
    private var currentSelectedTimestamp: Long = System.currentTimeMillis()

    private val postLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            // When PostActivity is successful, refresh the list
            if (queryStartDate != null && queryEndDate != null) {
                fetchPostsForDate(queryStartDate!!, queryEndDate!!)
            }
        }
    }

    private val commentsActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(CommentsActivity.COMMENT_ADDED, false) == true) {
            if (queryStartDate != null && queryEndDate != null) {
                fetchPostsForDate(queryStartDate!!, queryEndDate!!)
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

        // Buttons
        val whatsOnMindButton = view.findViewById<View>(R.id.btn_whats_on_mind)
        val addImageButton = view.findViewById<View>(R.id.btn_add_image)
        val scheduleEventButton = view.findViewById<View>(R.id.btn_schedule_event)

        postAdapter = PostAdapter(
            onDeletePost = { post -> deletePost(post) },
            onCommentClick = { post ->
                val intent = Intent(requireContext(), CommentsActivity::class.java).apply {
                    putExtra("POST_ID", post.id)
                }
                commentsActivityLauncher.launch(intent)
            },
            isAnnouncement = false // This is NOT the announcement screen, so show badges
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = postAdapter

        // 1. CALENDAR SELECTION LOGIC
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val calendar = Calendar.getInstance()

            // Set the selected date
            calendar.set(year, month, dayOfMonth)
            currentSelectedTimestamp = calendar.timeInMillis

            // Update the list to show events for this day
            calendar.set(year, month, dayOfMonth, 0, 0, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            queryStartDate = calendar.time

            calendar.set(year, month, dayOfMonth, 23, 59, 59)
            queryEndDate = calendar.time

            fetchPostsForDate(queryStartDate!!, queryEndDate!!)
        }

        // 2. NORMAL POST BUTTONS (Post Now)
        val openPostNow = View.OnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", true)
                putExtra("SCHEDULED_DATE", 0L) // 0 means "Post Immediately"
            }
            postLauncher.launch(intent)
        }

        whatsOnMindButton.setOnClickListener(openPostNow)
        addImageButton.setOnClickListener(openPostNow)


        // 3. SCHEDULE BUTTON (Manual Time Picker)
        scheduleEventButton.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = currentSelectedTimestamp

            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)

            val timePickerDialog = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)

                    val intent = Intent(requireContext(), PostActivity::class.java).apply {
                        putExtra("admin", true)
                        putExtra("SCHEDULED_DATE", calendar.timeInMillis)
                    }
                    postLauncher.launch(intent)
                },
                currentHour,
                currentMinute,
                false // set to true for 24 hour mode, false for AM/PM
            )
            timePickerDialog.show()
        }

        // Load today's posts by default on startup
        loadTodaysPosts()
    }

    private fun loadTodaysPosts() {
        val calendar = Calendar.getInstance()
        currentSelectedTimestamp = calendar.timeInMillis // Default to "Now"

        val todayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
        queryStartDate = todayStart

        val todayEnd = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.time
        queryEndDate = todayEnd

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
                            admin = document.getBoolean("admin") ?: false,
                            isScheduled = document.getBoolean("isScheduled") ?: false // ✅ FIXED: Added missing parsing
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
        // ✅ FIXED: Removed loose code that was causing syntax errors
        db.collection("posts").document(post.id)
            .delete()
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                Toast.makeText(requireContext(), "Event deleted successfully", Toast.LENGTH_SHORT).show()
                if (queryStartDate != null && queryEndDate != null) {
                    fetchPostsForDate(queryStartDate!!, queryEndDate!!)
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Toast.makeText(requireContext(), "Error deleting event: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}