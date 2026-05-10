package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ProfileMenuActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_menu)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val closeButton = findViewById<ImageView>(R.id.close_icon)
        val userImage = findViewById<ShapeableImageView>(R.id.user_image)
        val userName = findViewById<TextView>(R.id.user_name)
        val userEmail = findViewById<TextView>(R.id.user_email)

        val menuProfile = findViewById<LinearLayout>(R.id.menu_profile)
        val menuHistory = findViewById<LinearLayout>(R.id.menu_history)
        val menuSharedRides = findViewById<LinearLayout>(R.id.menu_shared_rides)
        val menuLogout = findViewById<LinearLayout>(R.id.menu_logout)

        closeButton.setOnClickListener {
            finish()
        }

        // Load user data
        val currentUser = auth.currentUser
        if (currentUser != null) {
            userEmail.text = currentUser.email
            
            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val fName = document.getString("firstName") ?: ""
                        val lName = document.getString("lastName") ?: ""
                        userName.text = "$fName $lName".trim()

                        val imageUrl = document.getString("profileImageUrl")
                        if (!imageUrl.isNullOrEmpty()) {
                            Glide.with(this)
                                .load(imageUrl)
                                .placeholder(R.drawable.ic_blank)
                                .error(R.drawable.ic_blank)
                                .into(userImage)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("ProfileMenuActivity", "Error fetching user data", e)
                }
        }

        menuProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }

        menuHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        menuSharedRides.setOnClickListener {
            startActivity(Intent(this, SharedRidesActivity::class.java))
        }



        menuLogout.setOnClickListener {
            auth.signOut()
            val intent = Intent(this, MainMenuActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }
}
