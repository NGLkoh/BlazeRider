package com.aorv.blazerider

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SignInActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var loginPreferences: SharedPreferences
    private val PREFS_NAME = "loginPrefs"
    private val KEY_EMAIL = "email"
    private val KEY_PASSWORD = "password"
    private val KEY_REMEMBER_ME = "rememberMe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val forgetPassword = findViewById<TextView>(R.id.tvForgotPassword)
        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val btnSignIn = findViewById<Button>(R.id.btnSignIn)
        val cbRememberMe = findViewById<CheckBox>(R.id.cbRememberMe)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }

        loginPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (loginPreferences.getBoolean(KEY_REMEMBER_ME, false)) {
            email.setText(loginPreferences.getString(KEY_EMAIL, ""))
            password.setText(loginPreferences.getString(KEY_PASSWORD, ""))
            cbRememberMe.isChecked = true
        }

        forgetPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        btnSignIn.setOnClickListener {
            val emailText = email.text.toString().trim()
            val pwd = password.text.toString().trim()
            val isRememberMeChecked = cbRememberMe.isChecked // Capture state at the time of click

            if (emailText.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Please fill out all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(emailText, pwd)
                .addOnSuccessListener { result ->
                    val editor = loginPreferences.edit()
                    if (isRememberMeChecked) {
                        // Save credentials and the "remember me" flag
                        editor.putString(KEY_EMAIL, emailText)
                        editor.putString(KEY_PASSWORD, pwd)
                        editor.putBoolean(KEY_REMEMBER_ME, true)
                    } else {
                        // Clear all login preferences
                        editor.clear()
                    }
                    editor.commit() // Use synchronous commit for certainty

                    val uid = result.user?.uid ?: return@addOnSuccessListener

                    db.collection("users").document(uid).get()
                        .addOnSuccessListener { document ->
                            if (document.exists()) {
                                val verified = document.getBoolean("verified") ?: false
                                val stepCompleted = document.getLong("stepCompleted")?.toInt() ?: 1

                                val isAdmin = when (val adminValue = document.get("admin")) {
                                    is Boolean -> adminValue
                                    is String -> adminValue.toBooleanStrictOrNull() ?: false
                                    else -> false
                                }

                                if (isAdmin) {
                                    startActivity(Intent(this, AdminActivity::class.java))
                                } else if (verified) {
                                    startActivity(Intent(this, HomeActivity::class.java))
                                } else {
                                    val intent = when (stepCompleted) {
                                        1 -> Intent(this, EmailVerificationActivity::class.java)
                                        2 -> Intent(this, CurrentAddressActivity::class.java)
                                        3 -> Intent(this, AdminApprovalActivity::class.java)
                                        else -> Intent(this, EmailVerificationActivity::class.java)
                                    }
                                    startActivity(intent)
                                }
                                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                                finish()
                            } else {
                                Toast.makeText(this, "User not registered. Please Sign Up to login", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this, "Failed to fetch user data: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Sign-in failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
