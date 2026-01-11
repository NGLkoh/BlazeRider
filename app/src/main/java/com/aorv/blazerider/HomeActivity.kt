package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private var lastClickTime: Long = 0
    private val debounceDelay: Long = 500 // 500ms debounce period
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationViewModel: LocationViewModel
    private var announcementsListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Notification Banner
    private lateinit var notificationBanner: CardView
    private lateinit var notificationTitle: TextView
    private lateinit var notificationMessage: TextView
    private lateinit var notificationDismiss: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        notificationBanner = findViewById(R.id.notification_banner)
        notificationTitle = notificationBanner.findViewById(R.id.notification_title)
        notificationMessage = notificationBanner.findViewById(R.id.notification_message)
        notificationDismiss = notificationBanner.findViewById(R.id.notification_dismiss)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply insets to the notification banner
            notificationBanner.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }

            // Apply insets to the main container
            val fragmentContainer = findViewById<FrameLayout>(R.id.fragment_container)
            fragmentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                if (isStatusBarTransparent()) {
                    topMargin = 0
                } else {
                    topMargin = insets.top
                }
            }

            WindowInsetsCompat.CONSUMED
        }

        if (savedInstanceState == null) {
            replaceFragment(LocationFragment())
            bottomNav.selectedItemId = R.id.nav_location
        }

        bottomNav.setOnItemSelectedListener { item ->
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastClickTime < debounceDelay) {
                false
            } else {
                lastClickTime = currentTime
                when (item.itemId) {
                    R.id.nav_location -> {
                        replaceFragment(LocationFragment())
                        true
                    }
                    R.id.nav_announcements -> {
                        replaceFragment(AnnouncementsFragment())
                        updateLastReadAnnouncement()
                        bottomNav.removeBadge(R.id.nav_announcements)
                        true
                    }
                    R.id.nav_home -> {
                        replaceFragment(FeedFragment())
                        true
                    }
                    R.id.nav_shared_rides -> {
                        startActivity(Intent(this, SharedRidesActivity::class.java))
                        true
                    }
                    R.id.nav_hamburger -> {
                        replaceFragment(MoreFragment())
                        true
                    }
                    else -> false
                }
            }
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                MaterialAlertDialogBuilder(this@HomeActivity)
                    .setTitle("Exit")
                    .setMessage("Are you sure you want to exit the app?")
                    .setPositiveButton("Yes") { _, _ ->
                        finishAffinity()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        checkNotificationPermission()
        listenForNotifications()
        listenForAnnouncements()
    }

    override fun onResume() {
        super.onResume()
        val userId = auth.currentUser?.uid ?: return
        locationViewModel.lastKnownLocation.observe(this) { location ->
            val statusData = mapOf(
                "state" to "online",
                "lastActive" to System.currentTimeMillis(),
                "location" to mapOf(
                    "latitude" to (location?.latitude ?: 0.0),
                    "longitude" to (location?.longitude ?: 0.0)
                )
            )
            FirebaseDatabase.getInstance().reference
                .child("status").child(userId)
                .setValue(statusData)
        }
    }

    override fun onPause() {
        super.onPause()
        val userId = auth.currentUser?.uid ?: return
        val location = locationViewModel.lastKnownLocation.value
        val statusData = mapOf(
            "state" to "offline",
            "lastActive" to System.currentTimeMillis(),
            "location" to mapOf(
                "latitude" to (location?.latitude ?: 0.0),
                "longitude" to (location?.longitude ?: 0.0)
            )
        )
        FirebaseDatabase.getInstance().reference
            .child("status").child(userId)
            .setValue(statusData)
    }

    override fun onDestroy() {
        super.onDestroy()
        announcementsListener?.remove()
        notificationsListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun checkNotificationPermission() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasSeenPrompt = sharedPreferences.getBoolean("has_seen_notification_prompt", false)

        if (!hasSeenPrompt && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enable Notifications")
                .setMessage("Notifications are disabled. Enable them to stay updated.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                    sharedPreferences.edit().putBoolean("has_seen_notification_prompt", true).apply()
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                    sharedPreferences.edit().putBoolean("has_seen_notification_prompt", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun isStatusBarTransparent(): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        return fragment is LocationFragment || fragment is FeedFragment
    }

    private fun replaceFragment(fragment: Fragment) {
        if (fragment is LocationFragment || fragment is FeedFragment) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = resources.getColor(R.color.red_orange, theme)
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun showNotificationBanner(title: String, message: String, notificationId: String, type: String?, entityId: String?) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            
            notificationTitle.text = title
            notificationMessage.text = message
            notificationBanner.visibility = View.VISIBLE

            notificationBanner.setOnClickListener {
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    db.collection("users").document(userId)
                        .collection("notifications").document(notificationId)
                        .update("isRead", true)
                }

                when (type) {
                    "reaction" -> {
                        if (entityId != null) {
                            val intent = Intent(this, SinglePostActivity::class.java).apply {
                                putExtra("POST_ID", entityId)
                            }
                            startActivity(intent)
                        }
                    }
                    "comment" -> {
                        if (entityId != null) {
                            val intent = Intent(this, CommentsActivity::class.java).apply {
                                putExtra("POST_ID", entityId)
                            }
                            startActivity(intent)
                        }
                    }
                    "message" -> {
                        if (entityId != null) {
                            val intent = Intent(this, ChatConversationActivity::class.java).apply {
                                putExtra("chatId", entityId)
                            }
                            startActivity(intent)
                        }
                    }
                }
                
                notificationBanner.visibility = View.GONE
            }

            notificationDismiss.setOnClickListener {
                notificationBanner.visibility = View.GONE
            }

            mainHandler.removeCallbacksAndMessages("banner_timeout")
            mainHandler.postAtTime({
                if (!isFinishing && !isDestroyed) {
                    notificationBanner.visibility = View.GONE
                }
            }, "banner_timeout", SystemClock.uptimeMillis() + 5000)
        }
    }

    private fun listenForNotifications() {
        val userId = auth.currentUser?.uid ?: return
        val prefs = getSharedPreferences("shown_notifications", MODE_PRIVATE)

        notificationsListener = db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("HomeActivity", "Error listening for notifications", e)
                    return@addSnapshotListener
                }

                // Optimization: Ignore local changes (optimistic updates)
                if (snapshot != null && snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

                if (snapshot != null && !snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val notificationId = doc.id
                    
                    try {
                        val notification = doc.toObject(Notification::class.java)
                        
                        if (notification != null && !notification.isRead && !prefs.getBoolean(notificationId, false)) {
                            val title = when (notification.type) {
                                "reaction" -> "New Reaction"
                                "comment" -> "New Comment"
                                "message" -> "New Message"
                                else -> "New Notification"
                            }
                            
                            val message = notification.message ?: "You have a new notification"
                            
                            showNotificationBanner(title, message, notificationId, notification.type, notification.entityId)
                            prefs.edit().putBoolean(notificationId, true).apply()
                        }
                    } catch (ex: Exception) {
                        Log.e("HomeActivity", "Error parsing notification object", ex)
                    }
                }
            }
    }

    private fun listenForAnnouncements() {
        val userId = auth.currentUser?.uid ?: return
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        db.collection("users").document(userId).addSnapshotListener { userDoc, _ ->
            if (userDoc == null || !userDoc.exists()) return@addSnapshotListener
            val lastRead = userDoc.getTimestamp("lastAnnouncementReadAt") ?: Timestamp(Date(0))
            
            announcementsListener?.remove()
            announcementsListener = db.collection("posts")
                .whereEqualTo("admin", true)
                .whereGreaterThan("createdAt", lastRead)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener

                    val unreadCount = snapshot?.size() ?: 0
                    if (unreadCount > 0) {
                        val badge = bottomNav.getOrCreateBadge(R.id.nav_announcements)
                        badge.isVisible = true
                        badge.number = unreadCount
                    } else {
                        bottomNav.removeBadge(R.id.nav_announcements)
                    }
                }
        }
    }

    private fun updateLastReadAnnouncement() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId)
            .update("lastAnnouncementReadAt", Timestamp.now())
    }
}
