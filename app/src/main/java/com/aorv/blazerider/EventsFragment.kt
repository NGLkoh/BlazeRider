package com.aorv.blazerider

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.MaterialCalendarView
import java.util.Calendar
import java.util.Date

class EventsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var calendarView: MaterialCalendarView // Updated Type
    private lateinit var postAdapter: PostAdapter
    private lateinit var userProfileImage: ShapeableImageView

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var queryStartDate: Date? = null
    private var queryEndDate: Date? = null

    // This holds the timestamp user selected (starts as "now")
    private var currentSelectedTimestamp: Long = System.currentTimeMillis()

    private val postLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            // Refresh list AND dots when a new post is made
            if (queryStartDate != null && queryEndDate != null) {
                fetchPostsForDate(queryStartDate!!, queryEndDate!!)
                loadEventDots() // Refresh dots
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
        userProfileImage = view.findViewById(R.id.user_image)

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
            isAnnouncement = false
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = postAdapter

        loadUserProfile()

        // --- LOAD DOTS FOR EVENTS ---
        loadEventDots()

        // 1. CALENDAR SELECTION LOGIC (Updated for MaterialCalendarView)
        calendarView.setOnDateChangedListener { _, date, selected ->
            if (selected) {
                val calendar = Calendar.getInstance()
                calendar.set(date.year, date.month - 1, date.day) // Month is 1-based in library, 0-based in Java Calendar

                currentSelectedTimestamp = calendar.timeInMillis

                // Set query range for the selected day
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                queryStartDate = calendar.time

                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                queryEndDate = calendar.time

                fetchPostsForDate(queryStartDate!!, queryEndDate!!)
            }
        }

        // Select today by default visually
        calendarView.setDateSelected(CalendarDay.today(), true)

        // 2. NORMAL POST BUTTONS (Post Now)
        val openPostNow = View.OnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", true)
                putExtra("SCHEDULED_DATE", 0L)
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
                false
            )
            timePickerDialog.show()
        }

        loadTodaysPosts()
    }

    // --- NEW: Fetch all event dates to show dots ---
    private fun loadEventDots() {
        db.collection("posts")
            .whereEqualTo("admin", true) // Only show dots for admin events
            .get()
            .addOnSuccessListener { result ->
                val eventDates = HashSet<CalendarDay>()

                for (document in result) {
                    val timestamp = document.getTimestamp("createdAt")
                    if (timestamp != null) {
                        val date = timestamp.toDate()
                        val calendar = Calendar.getInstance()
                        calendar.time = date

                        // Convert to CalendarDay for the library
                        val day = CalendarDay.from(
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1, // Library uses 1-12 for months
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                        eventDates.add(day)
                    }
                }

                // Apply the Red Dot Decorator
                // Color.RED or pass your custom color resource
                calendarView.addDecorator(EventDecorator(Color.parseColor("#E65100"), eventDates))
            }
            .addOnFailureListener {
                Log.e("EventsFragment", "Error loading event dots", it)
            }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user != null) {
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val profileImageUrl = document.getString("profileImageUrl")
                        if (context != null) {
                            Glide.with(this)
                                .load(profileImageUrl)
                                .placeholder(R.drawable.ic_anonymous)
                                .error(R.drawable.ic_anonymous)
                                .into(userProfileImage)
                        }
                    }
                }
        }
    }

    private fun loadTodaysPosts() {
        val calendar = Calendar.getInstance()
        currentSelectedTimestamp = calendar.timeInMillis

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
                            isScheduled = document.getBoolean("isScheduled") ?: false
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                postAdapter.submitPosts(fetchedPosts)
            }
    }

    private fun deletePost(post: Post) {
        db.collection("posts").document(post.id)
            .delete()
            .addOnSuccessListener {
                if (!isAdded) return@addOnSuccessListener
                Toast.makeText(requireContext(), "Event deleted", Toast.LENGTH_SHORT).show()
                if (queryStartDate != null && queryEndDate != null) {
                    fetchPostsForDate(queryStartDate!!, queryEndDate!!)
                    loadEventDots() // Refresh dots after delete
                }
            }
    }
}