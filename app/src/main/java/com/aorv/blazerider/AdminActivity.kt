package com.aorv.blazerider

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot

class AdminActivity : AppCompatActivity() {
    private val db = FirebaseFirestore.getInstance()
    private lateinit var bottomNavigationView: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make the app draw under system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_admin)

        bottomNavigationView = findViewById(R.id.admin_bottom_navigation)

        // Handle Window Insets for responsive bottom navigation (gesture nav vs buttons)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            // Adjust BottomNavigationView padding instead of margin
            // This makes the background color of the nav bar extend behind the system buttons
            bottomNavigationView.setPadding(0, 0, 0, navBarInsets.bottom)
            
            val fragmentContainer = findViewById<FrameLayout>(R.id.fragment_container)
            fragmentContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBarInsets.top
                // ConstraintLayout handles this via bottomNav top
                bottomMargin = 0
            }
            
            insets
        }

        if (savedInstanceState == null) {
            val initialTab = intent.getIntExtra("SELECT_TAB", R.id.nav_dashboard)
            bottomNavigationView.selectedItemId = initialTab
            
            val initialFragment: Fragment = when (initialTab) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_users -> UsersFragment().apply {
                    arguments = Bundle().apply { putString("initial_tab", "accepted") }
                }
                R.id.nav_events -> EventsFragment()
                R.id.nav_history -> AdminHistoryFragment()
                R.id.nav_more -> MoreFragment.newInstance(isAdmin = true)
                else -> DashboardFragment()
            }
            
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, initialFragment)
                .commit()
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            val selectedFragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_users -> UsersFragment().apply {
                    arguments = Bundle().apply { putString("initial_tab", "accepted") }
                }
                R.id.nav_events -> EventsFragment()
                R.id.nav_history -> AdminHistoryFragment()
                R.id.nav_more -> MoreFragment.newInstance(isAdmin = true)
                else -> DashboardFragment()
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, selectedFragment)
                .commit()
            true
        }

        updateUsersBadge()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val selectedTab = intent.getIntExtra("SELECT_TAB", -1)
        if (selectedTab != -1) {
            bottomNavigationView.selectedItemId = selectedTab
        }
    }

    private fun updateUsersBadge() {
        db.collection("users")
            .whereEqualTo("admin", false)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener
                val totalCount = value?.documents?.count {
                    it.getBoolean("verified") != true || it.getBoolean("verifiedRecent") == true
                } ?: 0

                val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_users)
                badge.number = totalCount
                badge.backgroundColor = resources.getColor(R.color.red_orange)
                badge.isVisible = totalCount > 0
            }
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Exit")
            .setMessage("Are you sure you want to exit the app?")
            .setPositiveButton("Yes") { _, _ -> finishAffinity() }
            .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}
