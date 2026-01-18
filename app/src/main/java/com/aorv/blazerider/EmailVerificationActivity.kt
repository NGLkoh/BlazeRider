package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
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
import java.util.Locale

class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val handler = Handler(Looper.getMainLooper())
    private val checkEmailVerificationInterval = 5000L // 5 seconds
    private val TAG = "EmailVerificationActivity"
    
    private lateinit var btnResend: TextView
    private var countDownTimer: CountDownTimer? = null
    private var isTimerRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_verification)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Get UI elements
        val btnVerify = findViewById<Button>(R.id.btnVerify)
        btnResend = findViewById<TextView>(R.id.btnResend)
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

        // Check email verification when Verify button is clicked
        btnVerify.setOnClickListener {
            currentUser.reload().addOnCompleteListener { task ->
                if (task.isSuccessful && currentUser.isEmailVerified) {
                    Log.d(TAG, "Email verified for user: ${currentUser.email}")
                    proceedToNextStep(currentUser.uid)
                } else {
                    Toast.makeText(this, "Email not verified yet. Please check your inbox.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Resend verification email when Resend text is clicked
        btnResend.setOnClickListener {
            if (isTimerRunning) return@setOnClickListener

            currentUser.sendEmailVerification().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Verification email sent to ${currentUser.email}")
                    Toast.makeText(this, "Verification email sent to ${currentUser.email}", Toast.LENGTH_SHORT).show()
                    startResendTimer()
                } else {
                    val error = task.exception?.message ?: "Unknown error"
                    Log.w(TAG, "Failed to send verification email: $error")
                    if (error.contains("too-many-requests")) {
                        Toast.makeText(this, "Too many requests. Please wait.", Toast.LENGTH_LONG).show()
                        startResendTimer() // Also start timer if Firebase blocks us
                    } else {
                        Toast.makeText(this, "Failed to resend verification email: $error", Toast.LENGTH_SHORT).show()
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
                        proceedToNextStep(currentUser.uid)
                    } else {
                        handler.postDelayed(this, checkEmailVerificationInterval)
                    }
                }
            }
        }
        handler.postDelayed(checkEmailVerificationRunnable, checkEmailVerificationInterval)

        // Logout functionality
        btnLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Confirm Logout")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Confirm") { _, _ ->
                    logoutUser(currentUser.uid)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startResendTimer() {
        btnResend.isEnabled = false
        isTimerRunning = true
        
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(120000, 1000) { // 2 minutes
            override fun onTick(millisUntilFinished: Long) {
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                val timeLeftFormatted = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                btnResend.text = "Resend email in $timeLeftFormatted"
            }

            override fun onFinish() {
                btnResend.text = "Resend verification email"
                btnResend.isEnabled = true
                isTimerRunning = false
            }
        }.start()
    }

    private fun proceedToNextStep(uid: String) {
        db.collection("users").document(uid)
            .update("stepCompleted", 2)
            .addOnSuccessListener {
                cleanup()
                Toast.makeText(this, "Email verified successfully!", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, CurrentAddressActivity::class.java))
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                finish()
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to update stepCompleted: ${it.message}")
                Toast.makeText(this, "Failed to update progress", Toast.LENGTH_SHORT).show()
            }
    }

    private fun logoutUser(uid: String) {
        val status = mapOf("state" to "offline", "last_changed" to System.currentTimeMillis())
        FirebaseDatabase.getInstance().getReference("status").child(uid).setValue(status)
            .addOnCompleteListener {
                auth.signOut()
                cleanup()
                startActivity(Intent(this, MainMenuActivity::class.java))
                finish()
            }
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        countDownTimer?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
