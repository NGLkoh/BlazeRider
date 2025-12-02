package com.aorv.blazerider

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class AnnouncementsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var postAdapter: PostAdapter
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var postsListener: ListenerRegistration? = null

    private val commentsActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(CommentsActivity.COMMENT_ADDED, false) == true) {
            loadAnnouncements()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate layout
        return inflater.inflate(R.layout.fragment_announcements, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ Apply window insets safely (attach to root view)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBarHeight)
            insets
        }

        // Setup RecyclerView
        recyclerView = view.findViewById(R.id.feed_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)
        postAdapter = PostAdapter(
            onDeletePost = { post -> deletePost(post) },
            onCommentClick = { post ->
                val intent = Intent(requireContext(), CommentsActivity::class.java).apply {
                    putExtra("POST_ID", post.id)
                }
                commentsActivityLauncher.launch(intent)
            }
        )
        recyclerView.adapter = postAdapter

        // Load current user’s profile picture (if you have one)
        val userImage = view.findViewById<ShapeableImageView?>(R.id.user_image)
        if (userImage != null) {
            loadUserProfile(userImage)
        }

        // Load admin-only posts
        loadAnnouncements()
    }

    private fun deletePost(post: Post) {
        db.collection("posts").document(post.id)
            .delete()
            .addOnSuccessListener {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Announcement deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error deleting post: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                Log.e("AnnouncementsFragment", "Error deleting post", e)
            }
    }

    private fun loadUserProfile(userImage: ShapeableImageView) {
        val user = auth.currentUser
        if (user != null) {
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val profileImageUrl = document.getString("profileImageUrl")
                        Glide.with(this)
                            .load(profileImageUrl)
                            .placeholder(R.drawable.ic_anonymous)
                            .error(R.drawable.ic_anonymous)
                            .into(userImage)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("AnnouncementsFragment", "Error loading user profile: ${e.message}")
                    Glide.with(this)
                        .load(R.drawable.ic_anonymous)
                        .into(userImage)
                }
        }
    }

    private fun loadAnnouncements() {
        postsListener?.remove()
        postsListener = db.collection("posts")
            .whereEqualTo("admin", true) // ✅ Filter: only admin posts
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("AnnouncementsFragment", "Error loading announcements: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val posts = snapshot.documents.mapNotNull { doc ->
                        val imageUris = doc.get("imageUris") as? List<String> ?: emptyList()
                        Log.d("AnnouncementsFragment", "Admin Post ID: ${doc.id}, Image URIs: $imageUris")
                        Post(
                            id = doc.id,
                            userId = doc.getString("userId") ?: "",
                            content = doc.getString("content") ?: "",
                            createdAt = doc.getTimestamp("createdAt")?.toDate(),
                            imageUris = imageUris,
                            reactionCount = doc.get("reactionCount") as? Map<String, Long> ?: emptyMap(),
                            commentsCount = doc.getLong("commentsCount") ?: 0,
                            admin = doc.getBoolean("admin") ?: false
                        )
                    }
                    postAdapter.submitPosts(posts)
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        postsListener?.remove()
    }
}
