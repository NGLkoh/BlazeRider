package com.aorv.blazerider

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
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

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_up)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Input Fields
        val firstName = findViewById<EditText>(R.id.firstName)
        val lastName = findViewById<EditText>(R.id.lastName)
        val email = findViewById<EditText>(R.id.email)
        val gender = findViewById<AutoCompleteTextView>(R.id.gender)
        val birthdate = findViewById<EditText>(R.id.birthdate)
        val password = findViewById<EditText>(R.id.password)
        val confirmPassword = findViewById<EditText>(R.id.confirmPassword)

        // Layout Containers for Error States (Turning Red)
        val firstNameLayout = findViewById<TextInputLayout>(R.id.firstNameLayout)
        val lastNameLayout = findViewById<TextInputLayout>(R.id.lastNameLayout)
        val emailLayout = findViewById<TextInputLayout>(R.id.emailLayout)
        val birthdateLayout = findViewById<TextInputLayout>(R.id.birthdateLayout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordLayout)
        val confirmPasswordLayout = findViewById<TextInputLayout>(R.id.confirmPasswordLayout)

        val btnSignUp = findViewById<Button>(R.id.btnSignUp)
        val assistance = findViewById<TextView>(R.id.passwordAssistance)
        val scrollView = findViewById<ScrollView>(R.id.scrollView)
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
                    birthdateLayout.error = null // Clear red error on selection
                },
                year, month, day
            )
            datePicker.datePicker.maxDate = System.currentTimeMillis()
            datePicker.show()
        }

        // Real-time Password Assistance
        password.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pwd = s.toString()
                passwordLayout.error = null // Remove red error while typing

                if (pwd.isNotEmpty() && password.hasFocus()) {
                    assistance.visibility = View.VISIBLE
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

        // Terms and Conditions logic
        termsAndConditions.setOnCheckedChangeListener { _, isChecked ->
            btnSignUp.isEnabled = isChecked
            if (isChecked) termsAndConditions.setTextColor(defaultColor)
        }

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // SIGN UP EXECUTION
        btnSignUp.setOnClickListener {
            // Hide indicator immediately
            assistance.visibility = View.GONE

            // Reset all errors to normal state
            val layouts = listOf(firstNameLayout, lastNameLayout, emailLayout, birthdateLayout, passwordLayout, confirmPasswordLayout)
            layouts.forEach { it.error = null }

            val fName = firstName.text.toString().trim()
            val lName = lastName.text.toString().trim()
            val emailText = email.text.toString().trim()
            val pwd = password.text.toString()
            val dobString = birthdate.text.toString()

            // 1. Basic empty check with Red Box triggers
            var hasError = false
            if (fName.isEmpty()) { firstNameLayout.error = "First name is required"; hasError = true }
            if (lName.isEmpty()) { lastNameLayout.error = "Last name is required"; hasError = true }
            if (emailText.isEmpty()) { emailLayout.error = "Email is required"; hasError = true }
            if (dobString.isEmpty()) { birthdateLayout.error = "Select birthdate"; hasError = true }
            if (pwd.isEmpty()) { passwordLayout.error = "Password is required"; hasError = true }

            if (hasError) {
                Toast.makeText(this, "Please fill out all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 2. Age Validation (18+)
            val parts = dobString.split("/")
            val birthDate = Calendar.getInstance().apply {
                set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
            }
            val today = Calendar.getInstance()
            var age = today.get(Calendar.YEAR) - birthDate.get(Calendar.YEAR)
            if (today.get(Calendar.DAY_OF_YEAR) < birthDate.get(Calendar.DAY_OF_YEAR)) age--

            if (age < 18) {
                birthdateLayout.error = "Must be 18 or older"
                Toast.makeText(this, "You must be at least 18 years old.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // 3. Password match check
            if (pwd != confirmPassword.text.toString()) {
                confirmPasswordLayout.error = "Passwords do not match"
                return@setOnClickListener
            }

            // 4. Create Account
            auth.createUserWithEmailAndPassword(emailText, pwd)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    val userData = mapOf(
                        "firstName" to fName,
                        "lastName" to lName,
                        "email" to emailText,
                        "gender" to gender.text.toString(),
                        "birthdate" to dobString,
                        "accountCreated" to FieldValue.serverTimestamp(),
                        "stepCompleted" to 1,
                        "verified" to false
                    )

                    db.collection("users").document(uid).set(userData)

                    val status = mapOf("state" to "online", "last_changed" to System.currentTimeMillis())
                    FirebaseDatabase.getInstance().getReference("status").child(uid).setValue(status)

                    startActivity(Intent(this, EmailVerificationActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    finish()
                }
                .addOnFailureListener { exception ->
                    if (exception is FirebaseAuthUserCollisionException) {
                        emailLayout.error = "Email already exists"
                    } else {
                        Toast.makeText(this, "Sign-up failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }
}