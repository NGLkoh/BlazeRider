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

class HomeActivity : AppCompatActivity() {

    private var lastClickTime: Long = 0
    private val debounceDelay: Long = 500 // 500ms debounce period
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationViewModel: LocationViewModel

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
                // This logic seems reversed, but it correctly handles the fragment padding
                // when the status bar is transparent.
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
    }
    
    override fun onResume() {
        super.onResume()
        // Set user status to online
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
                .addOnSuccessListener {
                    Log.d("HomeActivity", "Status set to online for user: $userId")
                }
                .addOnFailureListener { e ->
                    Log.e("HomeActivity", "Failed to set online status: ${e.message}")
                }
        }
    }

    override fun onPause() {
        super.onPause()
        // Set user status to offline
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
            .addOnSuccessListener {
                Log.d("HomeActivity", "Status set to offline for user: $userId")
            }
            .addOnFailureListener { e ->
                Log.e("HomeActivity", "Failed to set offline status: ${e.message}")
            }
    }

    private fun checkNotificationPermission() {
        val sharedPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasSeenPrompt = sharedPreferences.getBoolean("has_seen_notification_prompt", false)

        if (!hasSeenPrompt && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enable Notifications")
                .setMessage("Notifications are disabled. Enable them to stay updated with important alerts.")
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

    private fun showNotificationBanner(title: String, message: String) {
        notificationTitle.text = title
        notificationMessage.text = message
        notificationBanner.visibility = View.VISIBLE

        notificationDismiss.setOnClickListener {
            notificationBanner.visibility = View.GONE
        }

        Handler(Looper.getMainLooper()).postDelayed({
            notificationBanner.visibility = View.GONE
        }, 5000) // Hide after 5 seconds
    }

    private fun listenForNotifications() {
        val userId = auth.currentUser?.uid ?: return

        db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("HomeActivity", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val notification = doc.toObject(Notification::class.java)
                    if (notification != null && !notification.isRead) {
                        val title = when (notification.type) {
                            "reaction" -> "New Reaction"
                            "comment" -> "New Comment"
                            "message" -> "New Message"
                            else -> "New Notification"
                        }
                        showNotificationBanner(title, notification.message)
                        // Mark as read to prevent showing again
                        doc.reference.update("isRead", true)
                    }
                }
            }
    }
}
