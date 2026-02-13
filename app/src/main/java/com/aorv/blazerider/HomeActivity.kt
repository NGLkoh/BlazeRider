package com.aorv.blazerider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import com.google.firebase.firestore.DocumentChange
import java.util.Date

class HomeActivity : AppCompatActivity() {

    private var lastClickTime: Long = 0
    private val debounceDelay: Long = 500
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationViewModel: LocationViewModel
    private var announcementsListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private var messageListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Notification Banner UI
    private lateinit var notificationBanner: CardView
    private lateinit var notificationTitle: TextView
    private lateinit var notificationMessage: TextView
    private lateinit var notificationDismiss: ImageView
    
    // Local Broadcast Receiver for in-app banner messages from FCM
    private val messageBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MyFirebaseMessagingService.NEW_MESSAGE_ACTION) {
                val content = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_MESSAGE_CONTENT)
                val chatId = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_CHAT_ID)
                if (content != null) {
                    showNotificationBanner(
                        title = "New Message",
                        message = content,
                        type = "message",
                        entityId = chatId
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        // Initialize Notification Banner Views
        notificationBanner = findViewById(R.id.notification_banner)
        notificationTitle = notificationBanner.findViewById(R.id.notification_title)
        notificationMessage = notificationBanner.findViewById(R.id.notification_message)
        notificationDismiss = notificationBanner.findViewById(R.id.notification_dismiss)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            notificationBanner.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            val fragmentContainer = findViewById<FrameLayout>(R.id.fragment_container)
            fragmentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = if (isStatusBarTransparent()) 0 else insets.top
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
                    R.id.nav_location -> { replaceFragment(LocationFragment()); updateOnlineStatus(); true }
                    R.id.nav_announcements -> {
                        replaceFragment(AnnouncementsFragment())
                        updateLastReadAnnouncement()
                        bottomNav.removeBadge(R.id.nav_announcements)
                        true
                    }
                    R.id.nav_home -> { replaceFragment(FeedFragment()); updateOnlineStatus(); true }
                    R.id.nav_shared_rides -> {
                        startActivity(Intent(this, SharedRidesActivity::class.java))
                        true
                    }
                    R.id.nav_hamburger -> { replaceFragment(MoreFragment()); updateOnlineStatus(); true }
                    else -> false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                MaterialAlertDialogBuilder(this@HomeActivity)
                    .setTitle("Exit")
                    .setMessage("Are you sure you want to exit the app?")
                    .setPositiveButton("Yes") { _, _ -> finishAffinity() }
                    .setNegativeButton("No", null)
                    .show()
            }
        })

        checkNotificationPermission()
        listenForNotifications()
        listenForAnnouncements()
        listenForNewMessages()
    }

    private fun listenForNotifications() {
        val userId = auth.currentUser?.uid ?: return

        notificationsListener = db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || snapshot.isEmpty) return@addSnapshotListener

                val doc = snapshot.documents[0]
                val type = doc.getString("type") ?: "general"
                val entityId = doc.getString("entityId")
                val isRead = doc.getBoolean("isRead") ?: true

                // Corrected: Removed ::binding check because this activity uses findViewById
                if (!isRead) {
                    val title = doc.getString("title") ?: "New Notification"
                    val message = doc.getString("message") ?: ""
                    showNotificationBanner(title, message, type, entityId)
                }
            }
    }

    private fun listenForNewMessages() {
        val userId = auth.currentUser?.uid ?: return

        messageListener = db.collection("userChats").document(userId).collection("chats")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener

                for (dc in snapshot.documentChanges) {
                    if (dc.type == DocumentChange.Type.MODIFIED) {
                        val unreadCount = dc.document.getLong("unreadCount") ?: 0
                        val lastMessageMap = dc.document.get("lastMessage") as? Map<*, *>
                        val senderId = lastMessageMap?.get("senderId") as? String

                        if (unreadCount > 0 && senderId != userId) {
                            val content = lastMessageMap?.get("content") as? String ?: "New message"

                            if (senderId != null) {
                                db.collection("users").document(senderId).get().addOnSuccessListener { userDoc ->
                                    val name = "${userDoc.getString("firstName")} ${userDoc.getString("lastName")}".trim()
                                    showNotificationBanner(
                                        title = "New Message",
                                        message = "$name: $content",
                                        type = "message",
                                        entityId = dc.document.id
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun showNotificationBanner(title: String, message: String, type: String, entityId: String?) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post

            playNotificationSound()

            notificationTitle.text = title
            notificationMessage.text = message
            notificationBanner.visibility = View.VISIBLE

            notificationBanner.setOnClickListener {
                when (type) {
                    "message" -> startActivity(Intent(this, MessagesActivity::class.java))
                    "comment" -> entityId?.let {
                        startActivity(Intent(this, CommentsActivity::class.java).apply { putExtra("POST_ID", it) })
                    }
                    "reaction" -> entityId?.let {
                        startActivity(Intent(this, SinglePostActivity::class.java).apply { putExtra("POST_ID", it) })
                    }
                }
                notificationBanner.visibility = View.GONE
            }

            notificationDismiss.setOnClickListener { notificationBanner.visibility = View.GONE }

            mainHandler.removeCallbacksAndMessages("banner_timeout")

// Create the runnable for hiding the banner
            val hideBannerRunnable = Runnable {
                if (!isFinishing && !isDestroyed) {
                    notificationBanner.visibility = View.GONE
                }
            }

// Use postAtTime with the token to allow for cancellation
            mainHandler.postAtTime(
                hideBannerRunnable,
                "banner_timeout",
                SystemClock.uptimeMillis() + 5000
            )
        }
    }

    private fun playNotificationSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        updateOnlineStatus()
        // Register receiver to catch in-app banner notifications from background FCM messages
        LocalBroadcastManager.getInstance(this).registerReceiver(
            messageBroadcastReceiver,
            IntentFilter(MyFirebaseMessagingService.NEW_MESSAGE_ACTION)
        )
    }

    override fun onPause() {
        super.onPause()
        // Unregister receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageBroadcastReceiver)

        val userId = auth.currentUser?.uid ?: return
        val location = locationViewModel.lastKnownLocation.value
        val statusData = mapOf(
            "state" to "offline",
            "lastActive" to System.currentTimeMillis(),
            "location" to mapOf("latitude" to (location?.latitude ?: 0.0), "longitude" to (location?.longitude ?: 0.0))
        )
        FirebaseDatabase.getInstance().reference.child("status").child(userId).setValue(statusData)
    }

    override fun onDestroy() {
        super.onDestroy()
        announcementsListener?.remove()
        notificationsListener?.remove()
        messageListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
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
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
    }

    private fun isStatusBarTransparent(): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        return fragment is LocationFragment || fragment is FeedFragment
    }

    private fun checkNotificationPermission() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasSeenPrompt = sharedPreferences.getBoolean("has_seen_notification_prompt", false)
        if (!hasSeenPrompt && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            MaterialAlertDialogBuilder(this).setTitle("Enable Notifications").setMessage("Notifications are disabled. Enable them to stay updated.")
                .setPositiveButton("Go to Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, packageName) }
                    startActivity(intent)
                    sharedPreferences.edit().putBoolean("has_seen_notification_prompt", true).apply()
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                    sharedPreferences.edit().putBoolean("has_seen_notification_prompt", true).apply()
                }.setCancelable(false).show()
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
                .whereEqualTo("admin", true).whereGreaterThan("createdAt", lastRead)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
                    val unreadCount = snapshot?.size() ?: 0
                    if (unreadCount > 0) {
                        val badge = bottomNav.getOrCreateBadge(R.id.nav_announcements)
                        badge.isVisible = true; badge.number = unreadCount
                    } else { bottomNav.removeBadge(R.id.nav_announcements) }
                }
        }
    }

    private fun updateLastReadAnnouncement() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).update("lastAnnouncementReadAt", Timestamp.now())
    }
    
    private fun updateOnlineStatus() {
        val userId = auth.currentUser?.uid ?: return
        locationViewModel.lastKnownLocation.observe(this) { location ->
            val statusData = mapOf(
                "state" to "online",
                "lastActive" to System.currentTimeMillis(),
                "location" to mapOf("latitude" to (location?.latitude ?: 0.0), "longitude" to (location?.longitude ?: 0.0))
            )
            FirebaseDatabase.getInstance().reference.child("status").child(userId).setValue(statusData)
        }
    }
}