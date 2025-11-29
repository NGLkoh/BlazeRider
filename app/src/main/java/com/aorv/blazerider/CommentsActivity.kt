package com.aorv.blazerider

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommentsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var recyclerView: RecyclerView
    private lateinit var commentsAdapter: CommentsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(R.layout.activity_comments)

        // Set up Toolbar
        val toolbar = findViewById<Toolbar>(R.id.comments_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Apply slide-up transition
        overridePendingTransition(R.anim.slide_up, R.anim.stay)

        // Set up close button
        findViewById<ImageButton>(R.id.comments_close_button).setOnClickListener {
            finish()
        }

        // Load current user profile image
        val profilePic = findViewById<ShapeableImageView>(R.id.comment_profile_pic)
        loadCurrentUserProfileImage(profilePic)

        // Get postId from intent
        val postId = intent.getStringExtra("POST_ID") ?: return

        // Set up RecyclerView
        recyclerView = findViewById(R.id.comment_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        commentsAdapter = CommentsAdapter()
        recyclerView.adapter = commentsAdapter

        // Load comments
        loadComments(postId)

        // Handle comment input
        val commentInput = findViewById<EditText>(R.id.comment_input)
        val submitButton = findViewById<ImageButton>(R.id.comment_submit_button)
        val addImageButton = findViewById<ImageButton>(R.id.comment_add_image_button)

        commentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                submitButton.isEnabled = s?.isNotEmpty() == true
                submitButton.alpha = if (s?.isNotEmpty() == true) 1.0f else 0.5f
            }
        })

        submitButton.setOnClickListener {
            val content = commentInput.text.toString().trim()
            if (content.isNotEmpty()) {
                submitComment(postId, content)
                commentInput.text.clear()
            }
        }

        addImageButton.setOnClickListener {
            // TODO: Implement image picking logic
        }
    }

    private fun loadCurrentUserProfileImage(profilePic: ShapeableImageView) {
        val user = auth.currentUser ?: return
        db.collection("users").document(user.uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val profileImageUrl = document.getString("profileImageUrl")
                    if (!profileImageUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(profileImageUrl)
                            .placeholder(R.drawable.ic_anonymous)
                            .error(R.drawable.ic_anonymous)
                            .into(profilePic)
                    } else {
                        profilePic.setImageResource(R.drawable.ic_anonymous)
                    }
                } else {
                    profilePic.setImageResource(R.drawable.ic_anonymous)
                }
            }
            .addOnFailureListener {
                profilePic.setImageResource(R.drawable.ic_anonymous)
            }
    }

    private fun loadComments(postId: String) {
        val noCommentsLayout = findViewById<LinearLayout>(R.id.no_comments_layout)
        db.collection("posts").document(postId)
            .collection("comments")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null) {
                    val comments = mutableListOf<Comment>()
                    snapshot.documents.forEach { doc ->
                        val userId = doc.getString("userId") ?: return@forEach
                        db.collection("users").document(userId).get()
                            .addOnSuccessListener { userDoc ->
                                if (userDoc.exists()) {
                                    val comment = Comment(
                                        id = doc.id,
                                        userId = userId,
                                        firstName = userDoc.getString("firstName") ?: "",
                                        lastName = userDoc.getString("lastName") ?: "",
                                        profileImageUrl = userDoc.getString("profileImageUrl") ?: "",
                                        content = doc.getString("content") ?: "",
                                        createdAt = doc.getTimestamp("createdAt")?.toDate(),
                                        imageUris = doc.get("imageUris") as? List<String> ?: emptyList(),
                                        reactionCount = doc.get("reactionCount") as? Map<String, Long> ?: mapOf(
                                            "angry" to 0L,
                                            "haha" to 0L,
                                            "like" to 0L,
                                            "love" to 0L,
                                            "sad" to 0L,
                                            "wow" to 0L
                                        )
                                    )
                                    comments.add(comment)
                                    // Sort comments by createdAt to maintain order
                                    comments.sortBy { it.createdAt }
                                    commentsAdapter.submitComments(comments)
                                    // Toggle visibility of RecyclerView and no comments layout
                                    recyclerView.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
                                    noCommentsLayout.visibility = if (comments.isEmpty()) View.VISIBLE else View.GONE
                                }
                            }
                    }
                    // Handle case when snapshot is empty (no documents)
                    if (snapshot.documents.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        noCommentsLayout.visibility = View.VISIBLE
                    }
                }
            }
    }

    private fun submitComment(postId: String, content: String) {
        val user = auth.currentUser ?: return
        val comment = hashMapOf(
            "userId" to user.uid,
            "content" to content,
            "createdAt" to FieldValue.serverTimestamp(),
            "imageUris" to emptyList<String>(),
            "reactionCount" to mapOf(
                "angry" to 0L,
                "haha" to 0L,
                "like" to 0L,
                "love" to 0L,
                "sad" to 0L,
                "wow" to 0L
            )
        )
        db.collection("posts").document(postId)
            .collection("comments")
            .add(comment)
            .addOnSuccessListener { 
                db.collection("posts").document(postId).update("commentsCount", FieldValue.increment(1))
            }
    }

    private fun deleteComment(postId: String, commentId: String) {
        db.collection("posts").document(postId)
            .collection("comments").document(commentId)
            .delete()
            .addOnSuccessListener { 
                db.collection("posts").document(postId).update("commentsCount", FieldValue.increment(-1))
            }
    }

    override fun finish() {
        super.finish()
        // Apply slide-down transition
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }
}

class CommentsAdapter : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    private var comments = listOf<Comment>()

    fun submitComments(newComments: List<Comment>) {
        comments = newComments
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentViewHolder(view)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(comments[position])
    }

    override fun getItemCount(): Int = comments.size

    inner class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profilePic = itemView.findViewById<ShapeableImageView>(R.id.item_comment_profile_pic)
        private val userName = itemView.findViewById<TextView>(R.id.comment_user_name)
        private val timestamp = itemView.findViewById<TextView>(R.id.comment_timestamp)
        private val content = itemView.findViewById<TextView>(R.id.comment_content)
        private val imageContainer = itemView.findViewById<ConstraintLayout>(R.id.comment_image_container)
        private val imageViewPager = itemView.findViewById<ViewPager2>(R.id.comment_image_view_pager)
        private val imageCounter = itemView.findViewById<TextView>(R.id.comment_image_counter)
        private val likeButton = itemView.findViewById<ImageButton>(R.id.comment_like_button)

        fun bind(comment: Comment) {
            // Profile picture for commenter
            if (comment.profileImageUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(comment.profileImageUrl)
                    .placeholder(R.drawable.ic_anonymous)
                    .error(R.drawable.ic_anonymous)
                    .into(profilePic)
            } else {
                profilePic.setImageResource(R.drawable.ic_anonymous)
            }

            // User name
            userName.text = "${comment.firstName} ${comment.lastName}".trim()

            // Timestamp
            timestamp.text = comment.createdAt?.let {
                SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault()).format(it)
            } ?: ""

            // Content
            content.text = comment.content

            // Images
            if (comment.imageUris.isNotEmpty()) {
                imageContainer.visibility = View.VISIBLE
                imageViewPager.adapter = ImagePagerAdapter(comment.imageUris)
                imageCounter.visibility = if (comment.imageUris.size > 1) View.VISIBLE else View.GONE
                if (comment.imageUris.size > 1) {
                    imageCounter.text = "1/${comment.imageUris.size}"
                }
                imageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        imageCounter.text = "${position + 1}/${comment.imageUris.size}"
                    }
                })
            } else {
                imageContainer.visibility = View.GONE
            }

            // Like button
            likeButton.setOnClickListener {
                // TODO: Implement like functionality for comments
                // Placeholder: Update Firestore with like data
            }
        }
    }

    inner class ImagePagerAdapter(private val imageUrls: List<String>) :
        RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(android.R.id.icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val imageView = ImageView(parent.context).apply {
                id = android.R.id.icon
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            return ViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            Glide.with(holder.imageView)
                .load(imageUrls[position])
                .placeholder(R.drawable.ic_error_image)
                .error(R.drawable.ic_error_image)
                .into(holder.imageView)
        }

        override fun getItemCount(): Int = imageUrls.size
    }
}