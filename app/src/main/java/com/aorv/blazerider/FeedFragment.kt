package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
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

class FeedFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var postAdapter: PostAdapter
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var postsListener: ListenerRegistration? = null
    private var unreadMessagesListener: ListenerRegistration? = null
    private lateinit var chatBadge: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_feed, container, false)

        val whatsOnMindButton = view.findViewById<TextView>(R.id.btn_whats_on_mind)
        val addImageButton = view.findViewById<ImageButton>(R.id.btn_add_image)
        chatBadge = view.findViewById(R.id.chat_badge)

        whatsOnMindButton.setOnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", false)
            }
            startActivity(intent)
        }
        addImageButton.setOnClickListener {
            val intent = Intent(requireContext(), PostActivity::class.java).apply {
                putExtra("admin", false)
            }
            startActivity(intent)
        }

        val chatButton = view.findViewById<ImageButton>(R.id.btn_chat)
        chatButton.setOnClickListener {
            val intent = Intent(requireContext(), MessagesActivity::class.java)
            startActivity(intent)
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainContent = view.findViewById<android.widget.LinearLayout>(R.id.main_content)
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { v, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBarHeight)
            insets
        }

        recyclerView = view.findViewById(R.id.feed_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(context)
        postAdapter = PostAdapter { post ->
            deletePost(post)
        }
        recyclerView.adapter = postAdapter

        val userImage = view.findViewById<ShapeableImageView>(R.id.user_image)
        loadUserProfile(userImage)

        loadPosts()
        setupUnreadMessagesListener()
    }

    private fun deletePost(post: Post) {
        db.collection("posts").document(post.id)
            .delete()
            .addOnSuccessListener {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Post deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Toast.makeText(requireContext(), "Error deleting post: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                Log.e("FeedFragment", "Error deleting post", e)
            }
    }

    private fun setupUnreadMessagesListener() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            chatBadge.visibility = View.GONE
            return
        }

        unreadMessagesListener = db.collection("chats")
            .whereArrayContains("participants", currentUser.uid)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("FeedFragment", "Error listening for unread messages: ${e.message}")
                    return@addSnapshotListener
                }

                var unreadCount = 0
                for (doc in snapshots!!.documents) {
                    val lastMessage = doc.get("lastMessage") as? Map<*, *>
                    if (lastMessage != null) {
                        val seenBy = lastMessage["seenBy"] as? List<*> ?: emptyList<Any>()
                        if (!seenBy.contains(currentUser.uid)) {
                            unreadCount++
                        }
                    }
                }

                if (unreadCount > 0) {
                    chatBadge.text = unreadCount.toString()
                    chatBadge.visibility = View.VISIBLE
                } else {
                    chatBadge.visibility = View.GONE
                }
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
                    Log.e("FeedFragment", "Error loading user profile: ${e.message}")
                    Glide.with(this)
                        .load(R.drawable.ic_anonymous)
                        .into(userImage)
                }
        }
    }
    private fun loadPosts() {
        postsListener = db.collection("posts")
            .whereEqualTo("admin", false)  // Only fetch non-admin posts
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("FeedFragment", "Error loading posts: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val posts = snapshot.documents.mapNotNull { doc ->
                        val imageUris = doc.get("imageUris") as? List<String> ?: emptyList()
                        Log.d("FeedFragment", "Post ID: ${doc.id}, Image URIs: $imageUris")
                        Post(
                            id = doc.id,
                            userId = doc.getString("userId") ?: "",
                            content = doc.getString("content") ?: "",
                            createdAt = doc.getTimestamp("createdAt")?.toDate(),
                            imageUris = imageUris,
                            reactionCount = doc.get("reactionCount") as? Map<String, Long> ?: emptyMap(),
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
        unreadMessagesListener?.remove()
    }
}
