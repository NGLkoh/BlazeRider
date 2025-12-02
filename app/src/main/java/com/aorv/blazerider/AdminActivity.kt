package com.aorv.blazerider

import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot

class AdminActivity : AppCompatActivity() {
    private val mAuth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private lateinit var bottomNavigationView: BottomNavigationView // Use lateinit for non-nullable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        bottomNavigationView = findViewById(R.id.admin_bottom_navigation)

        // Set default fragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
        }

        // Handle bottom navigation selection
        bottomNavigationView.setOnItemSelectedListener { item: MenuItem ->
            var selectedFragment: Fragment? = null
            when (item.itemId) {
                R.id.nav_dashboard -> selectedFragment = DashboardFragment()
                R.id.nav_users -> {
                    val args = Bundle().apply {
                        putString("initial_tab", "accepted")
                    }
                    selectedFragment = UsersFragment().apply {
                        arguments = args
                    }
                }
                R.id.nav_events -> selectedFragment = EventsFragment()
                R.id.nav_more -> selectedFragment = MoreFragment.newInstance(isAdmin = true) // Pass isAdmin
            }

            if (selectedFragment != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, selectedFragment)
                    .commit()
            }
            true
        }

        // Start listening for user updates
        updateUsersBadge()
    }

    private fun updateUsersBadge() {
        db.collection("users")
            .whereEqualTo("admin", false) // Only non-admin users
            .addSnapshotListener { value: QuerySnapshot?, error: FirebaseFirestoreException? ->
                if (error != null) {
                    Log.e("AdminActivity", "Error fetching users: ${error.message}")
                    return@addSnapshotListener
                }
                if (value != null) {
                    var pendingCount = 0
                    var recentVerifiedCount = 0

                    for (document in value.documents) {
                        val verified = document.getBoolean("verified")
                        val verifiedRecent = document.getBoolean("verifiedRecent")

                        // Count pending (verified=false)
                        if (verified == null || !verified) {
                            pendingCount++
                        } else if (verifiedRecent != null && verifiedRecent) {
                            recentVerifiedCount++
                        }
                    }

                    val totalCount = pendingCount + recentVerifiedCount
                    Log.d(
                        "AdminActivity",
                        "Pending count: $pendingCount, Recent verified count: $recentVerifiedCount, Total: $totalCount"
                    )

                    // Update badge
                    val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_users)
                    badge.number = totalCount
                    badge.backgroundColor = resources.getColor(R.color.red_orange)
                    badge.isVisible = totalCount >= 1 // Show if 1 or more, hide if 0
                } else {
                    Log.w("AdminActivity", "Snapshot is null")
                    val badge = bottomNavigationView.getOrCreateBadge(R.id.nav_users)
                    badge.isVisible = false // Hide if no data
                }
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