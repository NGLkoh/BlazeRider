package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class AdminApprovalActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val handler = Handler(Looper.getMainLooper())
    private val checkApprovalInterval = 5000L // 5 seconds
    private val TAG = "AdminApprovalActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_approval)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Get UI elements
        val btnLogout = findViewById<TextView>(R.id.btnLogout)

        // Check if user is logged in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "No user logged in, redirecting to MainMenuActivity")
            Toast.makeText(this, "No user logged in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainMenuActivity::class.java))
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            finish()
            return
        }

        // Periodic admin approval check (every 5 seconds)
        val checkApprovalRunnable = object : Runnable {
            override fun run() {
                db.collection("users").document(currentUser.uid).get()
                    .addOnSuccessListener { document ->
                        if (document.exists() && document.getBoolean("verified") == true) {
                            Log.d(TAG, "User approved: ${currentUser.email}")
                            // User is approved, update stepCompleted and proceed
                            db.collection("users").document(currentUser.uid)
                                .update("stepCompleted", 4)
                                .addOnSuccessListener {
                                    handler.removeCallbacksAndMessages(null)
                                    Toast.makeText(this@AdminApprovalActivity, "Profile approved", Toast.LENGTH_SHORT).show()
                                    startActivity(Intent(this@AdminApprovalActivity, HomeActivity::class.java))
                                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                                    finish()
                                }
                                .addOnFailureListener {
                                    Log.w(TAG, "Failed to update stepCompleted: ${it.message}")
                                    Toast.makeText(this@AdminApprovalActivity, "Failed to update progress", Toast.LENGTH_SHORT).show()
                                    // Schedule next check even if update fails
                                    handler.postDelayed(this, checkApprovalInterval)
                                }
                        } else {
                            Log.d(TAG, "User not yet approved: ${currentUser.email}")
                            // Schedule next check
                            handler.postDelayed(this, checkApprovalInterval)
                        }
                    }
                    .addOnFailureListener {
                        Log.w(TAG, "Failed to check approval status: ${it.message}")
                        Toast.makeText(this@AdminApprovalActivity, "Failed to check approval status", Toast.LENGTH_SHORT).show()
                        // Schedule next check
                        handler.postDelayed(this, checkApprovalInterval)
                    }
            }
        }
        handler.postDelayed(checkApprovalRunnable, checkApprovalInterval)

        // Logout functionality with confirmation dialog
        btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Confirm Logout")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Confirm") { _, _ ->
                    // Update Realtime Database with offline status
                    val uid = currentUser.uid
                    val status = mapOf(
                        "state" to "offline",
                        "last_changed" to System.currentTimeMillis()
                    )
                    FirebaseDatabase.getInstance().getReference("status").child(uid).setValue(status)
                        .addOnCompleteListener { task ->
                            if (!task.isSuccessful) {
                                Log.w(TAG, "Failed to update Realtime Database status: ${task.exception?.message}")
                                Toast.makeText(this, "Failed to update status", Toast.LENGTH_SHORT).show()
                            }
                            // Sign out
                            auth.signOut()

                            // Verify sign-out
                            if (auth.currentUser == null) {
                                Log.d(TAG, "Sign-out successful, redirecting to MainMenuActivity")
                                Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, MainMenuActivity::class.java))
                                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
                                finish()
                            } else {
                                Log.e(TAG, "Sign-out failed, user still logged in: ${auth.currentUser?.uid}")
                                Toast.makeText(this, "Sign-out failed, please try again", Toast.LENGTH_SHORT).show()
                            }
                        }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up handler callbacks to prevent memory leaks
        handler.removeCallbacksAndMessages(null)
    }
}