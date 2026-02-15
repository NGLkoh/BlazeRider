package com.aorv.blazerider

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class AddAdminActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var mainAuth: FirebaseAuth
    private lateinit var adminRecyclerView: RecyclerView
    private lateinit var adminAdapter: AdminAdapter
    private val adminList = mutableListOf<Map<String, Any>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_admin)

        db = FirebaseFirestore.getInstance()
        mainAuth = FirebaseAuth.getInstance()

        val emailLayout = findViewById<TextInputLayout>(R.id.emailLayout)
        val emailEditText = findViewById<TextInputEditText>(R.id.email)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordLayout)
        val passwordEditText = findViewById<TextInputEditText>(R.id.password)
        val confirmPasswordEditText = findViewById<TextInputEditText>(R.id.confirmPassword)
        val confirmLayout = findViewById<TextInputLayout>(R.id.confirmPasswordLayout)
        val btnAddAdmin = findViewById<Button>(R.id.btnAddAdmin)
        adminRecyclerView = findViewById(R.id.adminRecyclerView)

        // Checklist TextViews
        val checkLength = findViewById<TextView>(R.id.check_length)
        val checkUpper = findViewById<TextView>(R.id.check_upper)
        val checkLower = findViewById<TextView>(R.id.check_lower)
        val checkNumber = findViewById<TextView>(R.id.check_number)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // Setup RecyclerView
        adminRecyclerView.layoutManager = LinearLayoutManager(this)
        adminAdapter = AdminAdapter()
        adminRecyclerView.adapter = adminAdapter

        // Fetch Admins
        fetchAdmins()

        // Real-time Email validation clearing
        emailEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                emailLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Real-time Password Validation
        passwordEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val password = s.toString()
                passwordLayout.error = null

                updateCheck(checkLength, password.length >= 8, "At least 8 characters")
                updateCheck(checkUpper, password.any { it.isUpperCase() }, "One uppercase")
                updateCheck(checkLower, password.any { it.isLowerCase() }, "One lowercase")
                updateCheck(checkNumber, password.any { it.isDigit() }, "One number")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        confirmPasswordEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                confirmLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnAddAdmin.setOnClickListener {
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            val confirm = confirmPasswordEditText.text.toString().trim()

            // Reset errors
            emailLayout.error = null
            passwordLayout.error = null
            confirmLayout.error = null

            if (email.isEmpty()) {
                emailLayout.error = "Email is required"
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                passwordLayout.error = "Password is required"
                return@setOnClickListener
            }

            val isValid = password.length >= 8 && password.any { it.isUpperCase() } &&
                    password.any { it.isLowerCase() } && password.any { it.isDigit() }

            if (!isValid) {
                passwordLayout.error = "Password does not meet requirements"
                return@setOnClickListener
            }

            if (password != confirm) {
                confirmLayout.error = "Passwords do not match"
                return@setOnClickListener
            }

            // Create Admin Logic using a secondary Firebase app instance
            // This prevents the current admin session from being signed out
            val secondaryApp = try {
                FirebaseApp.getInstance("AdminCreationApp")
            } catch (e: Exception) {
                FirebaseApp.initializeApp(this, FirebaseApp.getInstance().options, "AdminCreationApp")
            }
            
            val secondaryAuth = FirebaseAuth.getInstance(secondaryApp)

            secondaryAuth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    val adminData = mapOf(
                        "uid" to uid,
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
                            Toast.makeText(this, "Admin account created successfully", Toast.LENGTH_SHORT).show()
                            emailEditText.text = null
                            passwordEditText.text = null
                            confirmPasswordEditText.text = null
                            secondaryAuth.signOut()
                        }
                }
                .addOnFailureListener { e ->
                    if (e is FirebaseAuthUserCollisionException) {
                        emailLayout.error = "This email address is already in use by another account."
                    } else {
                        Toast.makeText(this, e.localizedMessage ?: "Creation failed", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun fetchAdmins() {
        db.collection("users")
            .whereEqualTo("admin", true)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    adminList.clear()
                    for (doc in snapshot.documents) {
                        val data = doc.data?.toMutableMap()
                        if (data != null) {
                            data["uid"] = doc.id
                            adminList.add(data)
                        }
                    }
                    adminAdapter.submitList(adminList)
                }
            }
    }

    private fun removeAdmin(adminUid: String, adminName: String) {
        if (adminUid == mainAuth.currentUser?.uid) {
            Toast.makeText(this, "You cannot remove yourself", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Admin Account")
            .setMessage("Are you sure you want to delete the account for $adminName? This action cannot be undone and they will no longer have access to the app.")
            .setPositiveButton("Delete") { _, _ ->
                // Delete the document from Firestore
                db.collection("users").document(adminUid)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(this, "Admin account deleted from database", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "Failed to delete account record: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    inner class AdminAdapter : RecyclerView.Adapter<AdminAdapter.ViewHolder>() {
        private var items = listOf<Map<String, Any>>()

        fun submitList(newList: List<Map<String, Any>>) {
            items = newList.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val admin = items[position]
            val firstName = admin["firstName"] as? String ?: ""
            val lastName = admin["lastName"] as? String ?: ""
            val fullName = "$firstName $lastName"
            val uid = admin["uid"] as? String ?: ""
            
            holder.name.text = fullName
            holder.email.text = admin["email"] as? String ?: ""
            
            val profileUrl = admin["profileImageUrl"] as? String
            Glide.with(holder.itemView.context)
                .load(profileUrl)
                .placeholder(R.drawable.ic_anonymous)
                .into(holder.pic)

            holder.removeBtn.setOnClickListener {
                removeAdmin(uid, fullName)
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val pic = v.findViewById<ShapeableImageView>(R.id.admin_profile_pic)
            val name = v.findViewById<TextView>(R.id.admin_full_name)
            val email = v.findViewById<TextView>(R.id.admin_email)
            val removeBtn = v.findViewById<View>(R.id.btn_remove_admin)
        }
    }
}
