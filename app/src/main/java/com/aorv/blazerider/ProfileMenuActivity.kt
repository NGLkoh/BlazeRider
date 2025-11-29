package com.aorv.blazerider

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class ProfileMenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_menu)

        val closeButton = findViewById<ImageView>(R.id.close_icon)
        closeButton.setOnClickListener {
            finish() // Acts like the back button
        }
    }
}