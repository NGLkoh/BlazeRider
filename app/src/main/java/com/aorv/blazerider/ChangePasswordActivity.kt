package com.aorv.blazerider

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.aorv.blazerider.databinding.ActivityChangePasswordBinding // Update with your package name

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var binding: ActivityChangePasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Handle toolbar navigation
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // Password assistance TextWatcher for New Password
        binding.newPasswordText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val pwd = s.toString()
                val validText = buildString {
                    append(if (pwd.length >= 8) "✅" else "⨯").append(" At least 8 characters\n")
                    append(if (pwd.any { it.isUpperCase() }) "✅" else "⨯").append(" One uppercase\n")
                    append(if (pwd.any { it.isLowerCase() }) "✅" else "⨯").append(" One lowercase\n")
                    append(if (pwd.any { it.isDigit() }) "✅" else "⨯").append(" One number\n")
                    append(if (pwd.any { "!@#$%^&*".contains(it) }) "✅" else "⨯").append(" One special character")
                }
                binding.passwordAssistance.text = "Password Requirements:\n$validText"
                binding.passwordAssistance.visibility = if (pwd.isNotEmpty()) View.VISIBLE else View.GONE
            }
        })

        // Handle Save Changes button
        binding.btnSaveChanges.setOnClickListener {
            val currentPassword = binding.currentPasswordText.text.toString()
            val newPassword = binding.newPasswordText.text.toString()
            val retypeNewPassword = binding.retypeNewText.text.toString()

            // Validate inputs
            when {
                currentPassword.isEmpty() -> binding.currentPasswordText.error = "Enter current password"
                newPassword.isEmpty() -> binding.newPasswordText.error = "Enter new password"
                retypeNewPassword.isEmpty() -> binding.retypeNewText.error = "Retype new password"
                newPassword != retypeNewPassword -> binding.retypeNewText.error = "Passwords do not match"
                !isValidPassword(newPassword) -> binding.newPasswordText.error = "Password does not meet requirements"
                else -> {
                    // Re-authenticate user before changing password
                    val user = auth.currentUser
                    if (user != null && user.email != null) {
                        val credential = EmailAuthProvider.getCredential(user.email!!, currentPassword)
                        user.reauthenticate(credential).addOnCompleteListener { reauthTask ->
                            if (reauthTask.isSuccessful) {
                                // Update password
                                user.updatePassword(newPassword).addOnCompleteListener { updateTask ->
                                    if (updateTask.isSuccessful) {
                                        Toast.makeText(this, "Password updated successfully", Toast.LENGTH_SHORT).show()
                                        finish()
                                    } else {
                                        Toast.makeText(this, "Failed to update password: ${updateTask.exception?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                binding.currentPasswordText.error = "Incorrect current password"
                            }
                        }
                    } else {
                        Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Validate password requirements
    private fun isValidPassword(password: String): Boolean {
        return password.length >= 8 &&
                password.any { it.isUpperCase() } &&
                password.any { it.isLowerCase() } &&
                password.any { it.isDigit() } &&
                password.any { "!@#$%^&*".contains(it) }
    }
}