package com.aorv.blazerider

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ServerTimestamp

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val firstName = findViewById<EditText>(R.id.firstName)
        val lastName = findViewById<EditText>(R.id.lastName)
        val email = findViewById<EditText>(R.id.email)
        val gender = findViewById<AutoCompleteTextView>(R.id.gender)
        val birthdate = findViewById<EditText>(R.id.birthdate)
        val password = findViewById<EditText>(R.id.password)
        val confirmPassword = findViewById<EditText>(R.id.confirmPassword)
        val btnSignUp = findViewById<Button>(R.id.btnSignUp)
        val assistance = findViewById<TextView>(R.id.passwordAssistance)
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordLayout)
        val termsAndConditions = findViewById<CheckBox>(R.id.termsAndConditions)
        val defaultColor = termsAndConditions.textColors

        // Gender dropdown
        val genderOptions = listOf("Male", "Female", "Prefer not to say")
        gender.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, genderOptions))

        // Birthdate picker
        birthdate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePicker = DatePickerDialog(
                this,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val formatted = String.format("%02d/%02d/%04d", selectedDay, selectedMonth + 1, selectedYear)
                    birthdate.setText(formatted)
                },
                year, month, day
            )
            datePicker.datePicker.maxDate = System.currentTimeMillis()
            datePicker.show()
        }

        // Show/hide password tips and scroll to keep assistance visible
        password.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && password.text.isNotEmpty()) {
                assistance.visibility = View.VISIBLE
                scrollView.postDelayed({
                    val rect = Rect()
                    assistance.getGlobalVisibleRect(rect)
                    scrollView.smoothScrollTo(0, rect.bottom - scrollView.height / 2)
                }, 300)
            } else if (!hasFocus) {
                assistance.visibility = View.GONE
            }
        }

        // Ensure scrolling for other fields
        val fields = listOf(firstName, lastName, email, gender, birthdate, confirmPassword)
        fields.forEach { field ->
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scrollView.postDelayed({
                        val rect = Rect()
                        field.getGlobalVisibleRect(rect)
                        scrollView.smoothScrollTo(0, rect.top - 100) // Offset to show field clearly
                    }, 300)
                }
            }
        }

        // Password tips updates
        password.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && password.text.isNotEmpty()) {
                assistance.visibility = View.VISIBLE
                scrollView.postDelayed({
                    val rect = Rect()
                    assistance.getGlobalVisibleRect(rect)
                    scrollView.smoothScrollTo(0, rect.bottom - scrollView.height / 2)
                }, 300)
            } else if (!hasFocus) {
                assistance.visibility = View.GONE
            }
        }

// Password tips updates
        password.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pwd = s.toString()

                // Show assistance when user starts typing
                if (pwd.isNotEmpty() && password.hasFocus()) {
                    assistance.visibility = View.VISIBLE
                    scrollView.postDelayed({
                        val rect = Rect()
                        assistance.getGlobalVisibleRect(rect)
                        scrollView.smoothScrollTo(0, rect.bottom - scrollView.height / 2)
                    }, 100)
                } else if (pwd.isEmpty()) {
                    assistance.visibility = View.GONE
                }

                val validText = buildString {
                    append(if (pwd.length >= 8) "✅" else "⨯").append(" At least 8 characters\n")
                    append(if (pwd.any { it.isUpperCase() }) "✅" else "⨯").append(" One uppercase\n")
                    append(if (pwd.any { it.isLowerCase() }) "✅" else "⨯").append(" One lowercase\n")
                    append(if (pwd.any { it.isDigit() }) "✅" else "⨯").append(" One number\n")
                    append(if (pwd.any { "!@#\$%^&*".contains(it) }) "✅" else "⨯").append(" One special character")
                }
                assistance.text = "Password Assistance\n$validText"
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Terms and Conditions
        termsAndConditions.setOnCheckedChangeListener { _, isChecked ->
            btnSignUp.isEnabled = isChecked
            if(isChecked) {
                termsAndConditions.setTextColor(defaultColor)
            }
        }

        val termsText = "Before you proceed, kindly review our Terms and Conditions. These terms outline how you can properly use the app, how we protect your information, and what rules must be followed to ensure a safe and smooth experience. By continuing, you agree to comply with all the policies written in this agreement."
        termsAndConditions.setOnClickListener { v ->
            if (v is CheckBox && !v.isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Terms and Conditions")
                    .setMessage(termsText)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        // Back button
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }

        // Sign up
        btnSignUp.setOnClickListener {
            if (!termsAndConditions.isChecked) {
                Toast.makeText(this, "Please agree to the Terms and Conditions.", Toast.LENGTH_SHORT).show()
                termsAndConditions.setTextColor(Color.RED)
                return@setOnClickListener
            }

            val emailText = email.text.toString().trim()
            val pwd = password.text.toString()

            if (firstName.text.isEmpty() || lastName.text.isEmpty() || emailText.isEmpty() ||
                gender.text.isEmpty() || birthdate.text.isEmpty() || password.text.isEmpty() ||
                confirmPassword.text.isEmpty()) {
                Toast.makeText(this, "Please fill out all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (pwd != confirmPassword.text.toString()) {
                Toast.makeText(this, "Passwords do not match.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.createUserWithEmailAndPassword(emailText, pwd)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener

                    val userData = mapOf(
                        "firstName" to firstName.text.toString(),
                        "lastName" to lastName.text.toString(),
                        "email" to emailText,
                        "gender" to gender.text.toString(),
                        "accountCreated" to FieldValue.serverTimestamp(), // Use server timestamp
                        "stepCompleted" to 1,
                        "verified" to false
                    )

                    db.collection("users").document(uid).set(userData)

                    val status = mapOf(
                        "state" to "online",
                        "last_changed" to System.currentTimeMillis()
                    )
                    FirebaseDatabase.getInstance().getReference("status").child(uid).setValue(status)

                    startActivity(Intent(this, EmailVerificationActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)

                    finish()
                }
                .addOnFailureListener { exception ->
                    when (exception) {
                        is FirebaseAuthUserCollisionException -> {
                            Toast.makeText(this, "Email already exists.", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            Toast.makeText(this, "Sign-up failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }
    }
}
