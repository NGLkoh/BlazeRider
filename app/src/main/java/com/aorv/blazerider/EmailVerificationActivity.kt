package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val handler = Handler(Looper.getMainLooper())
    private val checkEmailVerificationInterval = 5000L // 5 seconds
    private val TAG = "EmailVerificationActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_verification)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Get UI elements
        val btnVerify = findViewById<Button>(R.id.btnVerify)
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

        // Send verification email if not already sent
        if (!currentUser.isEmailVerified) {
            currentUser.sendEmailVerification().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Verification email sent to ${currentUser.email}")
                    Toast.makeText(this, "Verification email sent to ${currentUser.email}", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "Failed to send verification email: ${task.exception?.message}")
                    Toast.makeText(this, "Failed to send verification email because it has already been sent multiple times", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Check email verification when Verify button is clicked
        btnVerify.setOnClickListener {
            currentUser.reload().addOnCompleteListener { task ->
                if (task.isSuccessful && currentUser.isEmailVerified) {
                    Log.d(TAG, "Email verified for user: ${currentUser.email}")
                    // Email is verified, update stepCompleted and proceed
                    db.collection("users").document(currentUser.uid)
                        .update("stepCompleted", 2)
                        .addOnSuccessListener {
                            handler.removeCallbacksAndMessages(null)
                            Toast.makeText(this, "Email verified", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this, CurrentAddressActivity::class.java))
                            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                            finish()
                        }
                        .addOnFailureListener {
                            Log.w(TAG, "Failed to update stepCompleted: ${it.message}")
                            Toast.makeText(this, "Failed to update progress", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Log.d(TAG, "Email not verified, resending verification email")
                    // Resend verification email if not verified
                    currentUser.sendEmailVerification().addOnCompleteListener { sendTask ->
                        if (sendTask.isSuccessful) {
                            Log.d(TAG, "Verification email resent to ${currentUser.email}")
                            Toast.makeText(this, "Verification email resent to ${currentUser.email}", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.w(TAG, "Failed to resend verification email: ${sendTask.exception?.message}")
                            Toast.makeText(this, "Failed to resend verification email", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        // Periodic email verification check (every 5 seconds)
        val checkEmailVerificationRunnable = object : Runnable {
            override fun run() {
                currentUser.reload().addOnCompleteListener { task ->
                    if (task.isSuccessful && currentUser.isEmailVerified) {
                        Log.d(TAG, "Periodic check: Email verified for user: ${currentUser.email}")
                        // Email is verified, update stepCompleted and proceed
                        db.collection("users").document(currentUser.uid)
                            .update("stepCompleted", 2)
                            .addOnSuccessListener {
                                handler.removeCallbacksAndMessages(null)
                                Toast.makeText(this@EmailVerificationActivity, "Email verified", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this@EmailVerificationActivity, CurrentAddressActivity::class.java))
                                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                                finish()
                            }
                            .addOnFailureListener {
                                Log.w(TAG, "Periodic check: Failed to update stepCompleted: ${it.message}")
                                Toast.makeText(this@EmailVerificationActivity, "Failed to update progress", Toast.LENGTH_SHORT).show()
                                // Schedule next check even if Firestore update fails
                                handler.postDelayed(this, checkEmailVerificationInterval)
                            }
                    } else {
                        Log.d(TAG, "Periodic check: Email not verified for user: ${currentUser.email}")
                        // Schedule next check
                        handler.postDelayed(this, checkEmailVerificationInterval)
                    }
                }
            }
        }
        handler.postDelayed(checkEmailVerificationRunnable, checkEmailVerificationInterval)

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