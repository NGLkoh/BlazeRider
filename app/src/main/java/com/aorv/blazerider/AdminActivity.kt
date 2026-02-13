package com.aorv.blazerider

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
        setContentView(R.layout.activity_admin)

        bottomNavigationView = findViewById(R.id.admin_bottom_navigation)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DashboardFragment())
                .commit()
        }

        bottomNavigationView.setOnItemSelectedListener { item ->
            val selectedFragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_users -> UsersFragment().apply {
                    arguments = Bundle().apply { putString("initial_tab", "accepted") }
                }
                R.id.nav_events -> EventsFragment()
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