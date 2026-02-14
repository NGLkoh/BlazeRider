package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class AccountDeactivatedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_deactivated)

        // Ensure user is signed out so they can't bypass this screen
        FirebaseAuth.getInstance().signOut()

        val btnBack = findViewById<Button>(R.id.btnBackToLogin)
        btnBack.setOnClickListener {
            // FIX: Use 'this@AccountDeactivatedActivity' instead of 'this'
            // FIX: Use 'SignInActivity::class.java' (since your login file is named SignInActivity)
            val intent = Intent(this@AccountDeactivatedActivity, SignInActivity::class.java)

            // Clear back stack so they can't press back to return here
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}