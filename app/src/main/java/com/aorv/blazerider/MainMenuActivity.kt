package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MainMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if the user is already logged in
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            // User is logged in, handle routing based on UID
            if (currentUser.uid == "A7USXq3qwFgCH4sov6mmPdtaGOn2") {
                startActivity(Intent(this, AdminActivity::class.java))
            } else {
                // For other users, redirect to SignInActivity so it can handle full routing logic
                // (checking Firestore for verified status, stepCompleted, and generic isAdmin flag)
                startActivity(Intent(this, SignInActivity::class.java))
            }
            finish()
            return
        }

        setContentView(R.layout.activity_main_menu)

        val btnSignIn = findViewById<Button>(R.id.btnSignIn)
        val btnSignUp = findViewById<Button>(R.id.btnSignUp)

        btnSignIn.setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
            overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }

        btnSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
            overridePendingTransition(R.anim.slide_in_up, R.anim.stay)
        }
    }
}
