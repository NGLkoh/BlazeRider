package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val email = findViewById<EditText>(R.id.email)
        val btnResetPassword = findViewById<Button>(R.id.btnResetPassword)

        // Pre-fill email from intent
        val emailText = intent.getStringExtra("email") ?: ""
        email.setText(emailText)

        // Back button
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_out_right, R.anim.slide_in_left)
        }

        // Reset Password button
        btnResetPassword.setOnClickListener {
            val inputEmail = email.text.toString().trim()

            if (inputEmail.isEmpty()) {
                Toast.makeText(this, "Please enter your email address.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!inputEmail.contains("@") || !inputEmail.contains(".")) {
                Toast.makeText(this, "Please enter a valid email address.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Check if email exists in Firestore users collection
            db.collection("users")
                .whereEqualTo("email", inputEmail)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.isEmpty) {
                        Toast.makeText(this, "No account found with this email.", Toast.LENGTH_SHORT).show()
                    } else {
                        // Email exists, send reset email
                        auth.sendPasswordResetEmail(inputEmail)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Password reset email sent.", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, SignInActivity::class.java))
                                overridePendingTransition(R.anim.slide_out_right, R.anim.slide_in_left)
                                finish()
                            }
                            .addOnFailureListener { exception ->
                                Toast.makeText(this, "Failed to send reset email: ${exception.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Error checking email: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}