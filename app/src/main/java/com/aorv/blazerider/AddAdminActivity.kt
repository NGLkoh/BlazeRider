package com.aorv.blazerider

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class AddAdminActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var mainAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_admin)

        db = FirebaseFirestore.getInstance()
        mainAuth = FirebaseAuth.getInstance()

        val emailEditText = findViewById<TextInputEditText>(R.id.email)
        val passwordEditText = findViewById<TextInputEditText>(R.id.password)
        val confirmPasswordEditText = findViewById<TextInputEditText>(R.id.confirmPassword)
        val confirmLayout = findViewById<TextInputLayout>(R.id.confirmPasswordLayout)
        val btnAddAdmin = findViewById<Button>(R.id.btnAddAdmin)

        // Checklist TextViews
        val checkLength = findViewById<TextView>(R.id.check_length)
        val checkUpper = findViewById<TextView>(R.id.check_upper)
        val checkLower = findViewById<TextView>(R.id.check_lower)
        val checkNumber = findViewById<TextView>(R.id.check_number)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // Real-time Password Validation
        passwordEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val password = s.toString()

                updateCheck(checkLength, password.length >= 8, "At least 8 characters")
                updateCheck(checkUpper, password.any { it.isUpperCase() }, "One uppercase")
                updateCheck(checkLower, password.any { it.isLowerCase() }, "One lowercase")
                updateCheck(checkNumber, password.any { it.isDigit() }, "One number")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnAddAdmin.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            val confirm = confirmPasswordEditText.text.toString().trim()

            // 1. Basic empty check
            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Complexity check (Must meet all requirements)
            val isValid = password.length >= 8 && password.any { it.isUpperCase() } &&
                    password.any { it.isLowerCase() } && password.any { it.isDigit() }

            if (!isValid) {
                Toast.makeText(this, "Password does not meet requirements", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 3. Match check
            if (password != confirm) {
                confirmLayout.error = "Passwords do not match"
                return@setOnClickListener
            } else {
                confirmLayout.error = null
            }

            // Create Admin Logic
            mainAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    val adminData = mapOf(
                        "email" to email,
                        "admin" to true,
                        "firstName" to "Admin",
                        "lastName" to "User",
                        "accountCreated" to FieldValue.serverTimestamp(),
                        "verified" to true,
                        "stepCompleted" to 4
                    )
                    db.collection("users").document(uid).set(adminData)
                        .addOnSuccessListener {
                            val intent = Intent(this, AdminActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun updateCheck(textView: TextView, isValid: Boolean, label: String) {
        if (isValid) {
            textView.text = "✓ $label"
            textView.setTextColor(Color.parseColor("#388E3C")) // Green
        } else {
            textView.text = "✕ $label"
            textView.setTextColor(Color.parseColor("#D32F2F")) // Red
        }
    }
}