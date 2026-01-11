package com.aorv.blazerider

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class SinglePostActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var postId: String? = null
    private var currentReaction: String? = null
    private val longClickHandler = Handler(Looper.getMainLooper())
    private var longClickRunnable: Runnable? = null

    // Views from item_post included in activity_single_post
    private lateinit var profilePic: ShapeableImageView
    private lateinit var userName: TextView
    private lateinit var timestamp: TextView
    private lateinit var content: TextView
    private lateinit var imageContainer: ConstraintLayout
    private lateinit var imageViewPager: ViewPager2
    private lateinit var imageCounter: TextView
    private lateinit var likeIcon: ImageView
    private lateinit var likeText: TextView
    private lateinit var commentContainer: LinearLayout
    private lateinit var reactionsCount: TextView
    private lateinit var commentsCount: TextView
    private lateinit var adminBadge: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_post)

        postId = intent.getStringExtra("POST_ID")
        if (postId == null) {
            finish()
            return
        }

        initViews()
        setupListeners()
        loadPostData()
    }

    private fun initViews() {
        val root = findViewById<View>(R.id.post_item_layout)
        profilePic = root.findViewById(R.id.post_profile_pic)
        userName = root.findViewById(R.id.post_user_name)
        timestamp = root.findViewById(R.id.post_timestamp)
        content = root.findViewById(R.id.post_content)
        imageContainer = root.findViewById(R.id.post_image_container)
        imageViewPager = root.findViewById(R.id.post_image_view_pager)
        imageCounter = root.findViewById(R.id.post_image_counter)
        likeIcon = root.findViewById(R.id.post_like_icon)
        likeText = root.findViewById(R.id.post_like_text)
        commentContainer = root.findViewById(R.id.comment_container)
        reactionsCount = root.findViewById(R.id.post_reactions_count)
        commentsCount = root.findViewById(R.id.post_comments_count)
        adminBadge = root.findViewById(R.id.post_admin_badge)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        likeIcon.setOnClickListener { postId?.let { handleLikeClick(it) } }

        longClickRunnable = Runnable { postId?.let { showReactionPicker(it) } }
        likeIcon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> longClickHandler.postDelayed(longClickRunnable!!, 200)
                MotionEvent.ACTION_UP -> longClickHandler.removeCallbacks(longClickRunnable!!)
            }
            false
        }

        commentContainer.setOnClickListener {
            val intent = Intent(this, CommentsActivity::class.java).apply {
                putExtra("POST_ID", postId)
            }
            startActivity(intent)
        }
    }

    private fun loadPostData() {
        val id = postId ?: return
        db.collection("posts").document(id).addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val postUserId = snapshot.getString("userId") ?: ""
            val postContent = snapshot.getString("content") ?: ""
            val postCreatedAt = snapshot.getTimestamp("createdAt")?.toDate()
            val isAdmin = snapshot.getBoolean("admin") ?: false
            val imageUris = snapshot.get("imageUris") as? List<String> ?: emptyList()
            val reactionCount = snapshot.get("reactionCount") as? Map<String, Long> ?: emptyMap()
            val numComments = snapshot.getLong("commentsCount") ?: 0L

            content.text = postContent
            timestamp.text = postCreatedAt?.let {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(it)
            } ?: ""
            adminBadge.visibility = if (isAdmin) View.VISIBLE else View.GONE

            // Load user info
            db.collection("users").document(postUserId).get().addOnSuccessListener { userDoc ->
                if (userDoc.exists()) {
                    userName.text = "${userDoc.getString("firstName")} ${userDoc.getString("lastName")}".trim()
                    Glide.with(this).load(userDoc.getString("profileImageUrl"))
                        .placeholder(R.drawable.ic_anonymous).into(profilePic)
                }
            }

            // Setup images
            if (imageUris.isNotEmpty()) {
                imageContainer.visibility = View.VISIBLE
                imageViewPager.adapter = PostAdapter.ImagePagerAdapter(imageUris)
                if (imageUris.size > 1) {
                    imageCounter.visibility = View.VISIBLE
                    imageCounter.text = "1/${imageUris.size}"
                    imageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            imageCounter.text = "${position + 1}/${imageUris.size}"
                        }
                    })
                }
            } else {
                imageContainer.visibility = View.GONE
            }

            // Update counts
            val totalReactions = reactionCount.values.sum()
            reactionsCount.text = if (totalReactions > 0) "$totalReactions reactions" else ""
            commentsCount.text = if (numComments > 0) (if (numComments == 1L) "1 comment" else "$numComments comments") else ""

            // Load current user's reaction
            auth.currentUser?.uid?.let { uid ->
                db.collection("posts").document(id).collection("reactions").document(uid).get()
                    .addOnSuccessListener { doc ->
                        currentReaction = doc.getString("reactionType")
                        updateLikeIcon(currentReaction)
                    }
            }
        }
    }

    private fun handleLikeClick(postId: String) {
        val oldReaction = currentReaction
        if (oldReaction == "like") {
            currentReaction = null
            removeReaction(postId, "like")
        } else {
            currentReaction = "like"
            updateReaction(postId, "like", oldReaction)
        }
    }

    private fun showReactionPicker(postId: String) {
        val inflater = LayoutInflater.from(this)
        val popupView = inflater.inflate(R.layout.layout_reaction_popup, null)
        val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

        val reactions = mapOf(
            R.id.reaction_like to "like", R.id.reaction_love to "love", R.id.reaction_laugh to "haha",
            R.id.reaction_wow to "wow", R.id.reaction_sad to "sad", R.id.reaction_angry to "angry"
        )

        reactions.forEach { (viewId, reactionType) ->
            popupView.findViewById<ImageView>(viewId).setOnClickListener {
                val oldReaction = currentReaction
                if (oldReaction != reactionType) {
                    currentReaction = reactionType
                    updateReaction(postId, reactionType, oldReaction)
                }
                popupWindow.dismiss()
            }
        }

        popupWindow.showAsDropDown(likeIcon, 0, -likeIcon.height - 200)
    }

    private fun updateLikeIcon(reactionType: String?) {
        val (iconRes, text) = when (reactionType) {
            "like" -> R.drawable.ic_fb_like to "Like"
            "love" -> R.drawable.ic_fb_love to "Love"
            "haha" -> R.drawable.ic_fb_laugh to "Haha"
            "wow" -> R.drawable.ic_fb_wow to "Wow"
            "sad" -> R.drawable.ic_fb_sad to "Sad"
            "angry" -> R.drawable.ic_fb_angry to "Angry"
            else -> R.drawable.ic_empty_like to "Like"
        }
        likeIcon.setImageResource(iconRes)
        likeText.text = text
        if (reactionType == null) {
            likeIcon.setColorFilter(ContextCompat.getColor(this, R.color.teal_200))
        } else {
            likeIcon.clearColorFilter()
        }
    }

    private fun updateReaction(postId: String, newReaction: String, oldReaction: String?) {
        val user = auth.currentUser ?: return
        updateLikeIcon(newReaction)
        val postRef = db.collection("posts").document(postId)
        val reactionRef = postRef.collection("reactions").document(user.uid)

        db.runTransaction { transaction ->
            val postSnapshot = transaction.get(postRef)
            val reactionCount = postSnapshot.get("reactionCount") as? Map<String, Long> ?: emptyMap()
            val updatedCount = reactionCount.toMutableMap()

            if (oldReaction != null && oldReaction != newReaction) {
                val currentOldCount = updatedCount[oldReaction] ?: 0L
                if (currentOldCount > 0) updatedCount[oldReaction] = currentOldCount - 1
            }
            updatedCount[newReaction] = (updatedCount[newReaction] ?: 0L) + 1

            transaction.set(reactionRef, mapOf("reactionType" to newReaction, "timestamp" to FieldValue.serverTimestamp(), "userId" to user.uid))
            transaction.update(postRef, "reactionCount", updatedCount)
            null
        }
    }

    private fun removeReaction(postId: String, oldReaction: String?) {
        val user = auth.currentUser ?: return
        updateLikeIcon(null)
        val postRef = db.collection("posts").document(postId)
        val reactionRef = postRef.collection("reactions").document(user.uid)

        db.runTransaction { transaction ->
            val post = transaction.get(postRef)
            val reactionCount = post.get("reactionCount") as? Map<String, Long> ?: emptyMap()
            val updatedCount = reactionCount.toMutableMap()

            if (oldReaction != null) {
                val currentCount = updatedCount[oldReaction] ?: 0L
                if (currentCount > 0) updatedCount[oldReaction] = currentCount - 1
            }

            transaction.delete(reactionRef)
            transaction.update(postRef, "reactionCount", updatedCount)
            null
        }
    }
}
