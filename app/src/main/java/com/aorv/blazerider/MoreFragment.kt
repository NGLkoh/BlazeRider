package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.firebase.firestore.ListenerRegistration

class MoreFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var isAdmin: Boolean = false // Default value
    private var notificationsListener: ListenerRegistration? = null

    companion object {
        private const val ARG_IS_ADMIN = "isAdmin"

        fun newInstance(isAdmin: Boolean = false): MoreFragment {
            val fragment = MoreFragment()
            val args = Bundle().apply {
                putBoolean(ARG_IS_ADMIN, isAdmin)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAdmin = arguments?.getBoolean(ARG_IS_ADMIN) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_more, container, false)

        // Initialize Firebase Auth and Firestore
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val historyLayout = view.findViewById<LinearLayout>(R.id.history)
        val changePasswordLayout = view.findViewById<LinearLayout>(R.id.change_password)
        val notificationLayout = view.findViewById<LinearLayout>(R.id.notification)
        val notificationCountTextView = view.findViewById<TextView>(R.id.notification_count)

        // Set visibility of edit_profile based on isAdmin
        view.findViewById<View>(R.id.edit_profile).visibility = if (!isAdmin) View.VISIBLE else View.GONE
        historyLayout.visibility = if (isAdmin) View.GONE else View.VISIBLE
        changePasswordLayout.visibility = if (isAdmin) View.GONE else View.VISIBLE
        notificationLayout.visibility = if (isAdmin) View.GONE else View.VISIBLE

        // Get current user
        val currentUser = auth.currentUser
        if (currentUser != null) {
            view.findViewById<TextView>(R.id.user_email).text = currentUser.email ?: "user@gmail.com"

            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val firstName = document.getString("firstName") ?: ""
                        val lastName = document.getString("lastName") ?: ""
                        val profileImageUrl = document.getString("profileImageUrl")

                        view.findViewById<TextView>(R.id.user_name).text = "$firstName $lastName"

                        profileImageUrl?.let { url ->
                            if (url.isNotEmpty()) {
                                Glide.with(this)
                                    .load(url)
                                    .apply(
                                        RequestOptions()
                                            .placeholder(R.drawable.ic_blank)
                                            .error(R.drawable.ic_blank)
                                    )
                                    .into(view.findViewById<ShapeableImageView>(R.id.user_image))
                            } else {
                                view.findViewById<ShapeableImageView>(R.id.user_image)
                                    .setImageResource(R.drawable.ic_blank)
                            }
                        } ?: run {
                            view.findViewById<ShapeableImageView>(R.id.user_image)
                                .setImageResource(R.drawable.ic_blank)
                        }
                    } else {
                        view.findViewById<TextView>(R.id.user_name).text = "User"
                        view.findViewById<ShapeableImageView>(R.id.user_image)
                            .setImageResource(R.drawable.ic_blank)
                    }
                }
                .addOnFailureListener {
                    view.findViewById<TextView>(R.id.user_name).text = "User"
                    view.findViewById<ShapeableImageView>(R.id.user_image)
                        .setImageResource(R.drawable.ic_blank)
                }

            // Listen for unread notifications
            notificationsListener = db.collection("users").document(currentUser.uid)
                .collection("notifications")
                .whereEqualTo("isRead", false)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        notificationCountTextView.visibility = View.GONE
                        return@addSnapshotListener
                    }

                    val unreadCount = snapshot?.size() ?: 0
                    if (unreadCount > 0) {
                        notificationCountTextView.text = unreadCount.toString()
                        notificationCountTextView.visibility = View.VISIBLE
                    } else {
                        notificationCountTextView.visibility = View.GONE
                    }
                }
        } else {
            view.findViewById<TextView>(R.id.user_name).text = "User"
            view.findViewById<TextView>(R.id.user_email).text = "user@gmail.com"
            view.findViewById<ShapeableImageView>(R.id.user_image)
                .setImageResource(R.drawable.ic_blank)
        }

        // Set click listener for edit profile (only relevant if visible)
        view.findViewById<View>(R.id.edit_profile).setOnClickListener {
            if (!isAdmin) { // Optional: Guard to ensure click not works for admins
                val intent = Intent(requireContext(), EditProfileActivity::class.java)
                startActivity(intent)
            }
        }

        view.findViewById<LinearLayout>(R.id.notification).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, NotificationsFragment.newInstance())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<LinearLayout>(R.id.history).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HistoryFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<LinearLayout>(R.id.change_password).setOnClickListener {
            val intent = Intent(requireContext(), ChangePasswordActivity::class.java)
            startActivity(intent)
        }

        view.findViewById<View>(R.id.logout_item).setOnClickListener {
            showLogoutDialog()
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        notificationsListener?.remove()
    }

    private fun showLogoutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                auth.signOut()
                val intent = Intent(requireContext(), MainMenuActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
