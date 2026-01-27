package com.aorv.blazerider // THIS LINE IS REQUIRED

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.aorv.blazerider.R

class FullScreenImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_image)

        val imageUrl = intent.getStringExtra("IMAGE_URL")
        val imageView = findViewById<ImageView>(R.id.full_screen_image)
        val closeButton = findViewById<View>(R.id.close_button)

        if (imageUrl != null && imageView != null) {
            Glide.with(this).load(imageUrl).into(imageView)
        }

        closeButton?.setOnClickListener { finish() }
    }
}