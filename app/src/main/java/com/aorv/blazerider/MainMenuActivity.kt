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
        if (FirebaseAuth.getInstance().currentUser != null) {
            // User is logged in, so redirect to HomeActivity
            startActivity(Intent(this, HomeActivity::class.java))
            finish() // Finish this activity so the user can't go back to it
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
