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
            val isRememberMeChecked = cbRememberMe.isChecked

            if (emailText.isEmpty() || pwd.isEmpty()) {
                Toast.makeText(this, "Please fill out all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.signInWithEmailAndPassword(emailText, pwd)
                .addOnSuccessListener { result ->
                    val editor = loginPreferences.edit()
                    if (isRememberMeChecked) {
                        editor.putString(KEY_EMAIL, emailText)
                        editor.putString(KEY_PASSWORD, pwd)
                        editor.putBoolean(KEY_REMEMBER_ME, true)
                    } else {
                        editor.clear()
                    }
                    editor.commit()

                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    handleUserRouting(uid)
                }
                .addOnFailureListener { exception ->
                    Toast.makeText(this, "Sign-in failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /**
     * This method runs when the activity starts.
     * It checks if a Firebase session already exists to skip the login screen.
     */
    override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // User is already logged in, bypass the sign-in UI
            handleUserRouting(currentUser.uid)
        }
    }

    /**
     * Logic to determine if the user goes to Admin, Home, or Onboarding
     */
    private fun handleUserRouting(uid: String) {
        // Special handling for the hardcoded admin user ID
        if (uid == "A7USXq3qwFgCH4sov6mmPdtaGOn2") {
            navigateTo(AdminActivity::class.java)
            return
        }

        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val verified = document.getBoolean("verified") ?: false
                    val stepCompleted = document.getLong("stepCompleted")?.toInt() ?: 1

                    val isAdmin = when (val adminValue = document.get("admin")) {
                        is Boolean -> adminValue
                        is String -> adminValue.toBooleanStrictOrNull() ?: false
                        is Long -> adminValue == 1L
                        else -> false
                    }

                    if (isAdmin) {
                        navigateTo(AdminActivity::class.java)
                    } else if (verified) {
                        navigateTo(HomeActivity::class.java)
                    } else {
                        val intentClass = when (stepCompleted) {
                            1 -> EmailVerificationActivity::class.java
                            2 -> CurrentAddressActivity::class.java
                            3 -> AdminApprovalActivity::class.java
                            else -> EmailVerificationActivity::class.java
                        }
                        navigateTo(intentClass)
                    }
                } else {
                    // This handles cases where the auth exists but document was deleted
                    auth.signOut()
                    Toast.makeText(this, "User data not found.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error fetching profile: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun navigateTo(cls: Class<*>) {
        val intent = Intent(this, cls)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        finish()
    }
}