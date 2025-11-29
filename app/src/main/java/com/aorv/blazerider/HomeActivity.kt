package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng

class HomeActivity : AppCompatActivity() {

    // Debounce variables
    private var lastClickTime: Long = 0
    private val debounceDelay: Long = 500 // 500ms debounce period
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationViewModel: LocationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Initialize Firebase and location services
        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = resources.getColor(R.color.red_orange, theme)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // Set default fragment to LocationFragment
        if (savedInstanceState == null) {
            replaceFragment(LocationFragment())
            bottomNav.selectedItemId = R.id.nav_location
        }

        bottomNav.setOnItemSelectedListener { item ->
            // Debounce logic
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastClickTime < debounceDelay) {
                false // Ignore click if within debounce period
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

        // Check and prompt for notification permission
        checkNotificationPermission()
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

    private fun replaceFragment(fragment: Fragment) {
        val fragmentContainer = findViewById<FrameLayout>(R.id.fragment_container)

        // Update status bar appearance and insets based on fragment
        if (fragment is LocationFragment || fragment is FeedFragment) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
            ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
                v.setPadding(0, 0, 0, 0)
                insets
            }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = resources.getColor(R.color.red_orange, theme)
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
            ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(0, statusBarHeight, 0, v.paddingBottom)
                insets
            }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }
}
