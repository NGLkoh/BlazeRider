package com.aorv.blazerider

import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class PostAdapter(
    private val onDeletePost: (Post) -> Unit,
    private val onCommentClick: (Post) -> Unit
) : RecyclerView.Adapter<PostAdapter.PostViewHolder>() {

    private var posts = listOf<Post>()

    fun submitPosts(newPosts: List<Post>) {
        posts = newPosts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return PostViewHolder(view)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        holder.bind(posts[position])
    }

    override fun getItemCount(): Int = posts.size

    override fun onViewRecycled(holder: PostViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    inner class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profilePic = itemView.findViewById<ShapeableImageView>(R.id.post_profile_pic)
        private val userName = itemView.findViewById<TextView>(R.id.post_user_name)
        private val timestamp = itemView.findViewById<TextView>(R.id.post_timestamp)
        private val content = itemView.findViewById<TextView>(R.id.post_content)
        private val imageContainer = itemView.findViewById<ConstraintLayout>(R.id.post_image_container)
        private val imageViewPager = itemView.findViewById<ViewPager2>(R.id.post_image_view_pager)
        private val imageCounter = itemView.findViewById<TextView>(R.id.post_image_counter)
        private val likeIcon = itemView.findViewById<ImageView>(R.id.post_like_icon)
        private val likeText = itemView.findViewById<TextView>(R.id.post_like_text)
        private val commentContainer = itemView.findViewById<LinearLayout>(R.id.comment_container)
        private val shareContainer = itemView.findViewById<LinearLayout>(R.id.share_container)
        private val reactionsCount = itemView.findViewById<TextView>(R.id.post_reactions_count)
        private val commentsCount = itemView.findViewById<TextView>(R.id.post_comments_count)
        private val adminBadge = itemView.findViewById<TextView>(R.id.post_admin_badge)
        private val postOptionsIcon = itemView.findViewById<ImageView>(R.id.post_options_icon)

        private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
        private val db = FirebaseFirestore.getInstance()
        private val auth = FirebaseAuth.getInstance()

        private val longClickHandler = Handler(Looper.getMainLooper())
        private var longClickRunnable: Runnable? = null
        private var currentReaction: String? = null

        fun cleanup() {
            pageChangeCallback?.let { imageViewPager.unregisterOnPageChangeCallback(it) }
            pageChangeCallback = null
            longClickRunnable?.let { longClickHandler.removeCallbacks(it) }
            longClickRunnable = null
        }

        fun bind(post: Post) {
            cleanup()

            if (post.userId == auth.currentUser?.uid) {
                postOptionsIcon.visibility = View.VISIBLE
                postOptionsIcon.setOnClickListener { showPostOptionsMenu(it, post) }
            } else {
                postOptionsIcon.visibility = View.GONE
            }


            // Load user data
            db.collection("users").document(post.userId).get()
                .addOnSuccessListener { document ->
                    if (adapterPosition == RecyclerView.NO_POSITION) return@addOnSuccessListener
                    if (document.exists()) {
                        val firstName = document.getString("firstName") ?: ""
                        val lastName = document.getString("lastName") ?: ""
                        userName.text = "$firstName $lastName".trim()
                        val profileImageUrl = document.getString("profileImageUrl")
                        Glide.with(itemView.context)
                            .load(profileImageUrl)
                            .placeholder(R.drawable.ic_anonymous)
                            .error(R.drawable.ic_anonymous)
                            .into(profilePic)
                    }
                }
                .addOnFailureListener { e ->
                    if (adapterPosition == RecyclerView.NO_POSITION) return@addOnFailureListener
                    Log.e("PostViewHolder", "Error loading user data: ${e.message}")
                    Glide.with(itemView.context)
                        .load(R.drawable.ic_anonymous)
                        .into(profilePic)
                }

            // Set post data
            content.text = post.content
            timestamp.text = post.createdAt?.let {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(it)
            } ?: ""

            // Display admin badge
            adminBadge?.visibility = if (post.admin) View.VISIBLE else View.GONE
            adminBadge?.text = "Admin"

            // Setup images
            if (post.imageUris.isNotEmpty()) {
                imageContainer.visibility = View.VISIBLE
                imageViewPager.adapter = ImagePagerAdapter(post.imageUris)
                imageViewPager.setCurrentItem(0, false)
                if (post.imageUris.size > 1) {
                    imageCounter.visibility = View.VISIBLE
                    imageCounter.text = "1/${post.imageUris.size}"
                    pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            if (adapterPosition == RecyclerView.NO_POSITION) return
                            imageCounter.text = "${position + 1}/${post.imageUris.size}"
                        }
                    }
                    pageChangeCallback?.let { imageViewPager.registerOnPageChangeCallback(it) }
                } else {
                    imageCounter.visibility = View.GONE
                }
            } else {
                imageContainer.visibility = View.GONE
                imageCounter.visibility = View.GONE
            }

            // Set counts
            val totalReactions = post.reactionCount.values.sum()
            reactionsCount.text = when { totalReactions > 0 -> "$totalReactions reactions" else -> "" }
            reactionsCount.setOnClickListener {
                if (totalReactions > 0) {
                    showReactionsDialog(post.id)
                }
            }
            val numComments = post.commentsCount
            if (numComments > 0) {
                commentsCount.visibility = View.VISIBLE
                commentsCount.text = if (numComments == 1L) "1 comment" else "$numComments comments"
            } else {
                commentsCount.visibility = View.GONE
            }

            // Load current reaction
            val user = auth.currentUser
            if (user != null) {
                db.collection("posts").document(post.id)
                    .collection("reactions").document(user.uid).get()
                    .addOnSuccessListener { document ->
                        if (adapterPosition == RecyclerView.NO_POSITION) return@addOnSuccessListener
                        val reactionType = document.getString("reactionType")
                        this.currentReaction = reactionType
                        updateLikeIcon(reactionType)
                    }
            } else {
                this.currentReaction = null
                updateLikeIcon(null)
            }

            // Like action
            likeIcon.setOnClickListener {
                handleLikeClick(post.id)
            }

            longClickRunnable = Runnable { showReactionPicker(post.id) }

            likeIcon.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> longClickHandler.postDelayed(longClickRunnable!!, 200)
                    MotionEvent.ACTION_UP -> longClickHandler.removeCallbacks(longClickRunnable!!)
                }
                false
            }

            // Comment action
            commentContainer.setOnClickListener {
                onCommentClick(post)
            }

            shareContainer.setOnClickListener { /* Implement share */ }
        }

        private fun showReactionsDialog(postId: String) {
            val dialogView = LayoutInflater.from(itemView.context).inflate(R.layout.dialog_reactions, null)
            val reactionsRecyclerView = dialogView.findViewById<RecyclerView>(R.id.reactions_recycler_view)
            reactionsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)

            db.collection("posts").document(postId).collection("reactions").get()
                .addOnSuccessListener { snapshot ->
                    val reactions = mutableListOf<Reaction>()
                    for (document in snapshot.documents) {
                        val reaction = document.toObject(Reaction::class.java)
                        if (reaction != null) {
                            db.collection("users").document(reaction.userId).get()
                                .addOnSuccessListener { userDoc ->
                                    val profilePicUrl = userDoc.getString("profileImageUrl") ?: ""
                                    reactions.add(reaction.copy(userProfilePictureUrl = profilePicUrl))
                                    if (reactions.size == snapshot.documents.size) {
                                        reactionsRecyclerView.adapter = ReactionsAdapter(reactions)
                                    }
                                }
                        }
                    }
                }

            AlertDialog.Builder(itemView.context)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show()
        }

        private fun showPostOptionsMenu(view: View, post: Post) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.post_options_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.delete_post -> {
                        showDeleteConfirmationDialog(post)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        private fun showDeleteConfirmationDialog(post: Post) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post?")
                .setPositiveButton("Delete") { _, _ ->
                    onDeletePost(post)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun handleLikeClick(postId: String) {
            val oldReaction = this.currentReaction
            if (oldReaction == "like") {
                this.currentReaction = null
                removeReaction(postId, "like")
            } else {
                this.currentReaction = "like"
                updateReaction(postId, "like", oldReaction)
            }
        }

        private fun showReactionPicker(postId: String) {
            val inflater = LayoutInflater.from(itemView.context)
            val popupView = inflater.inflate(R.layout.layout_reaction_popup, null)
            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

            val reactions = mapOf(
                R.id.reaction_like to "like", R.id.reaction_love to "love", R.id.reaction_laugh to "haha",
                R.id.reaction_wow to "wow", R.id.reaction_sad to "sad", R.id.reaction_angry to "angry"
            )

            reactions.forEach { (viewId, reactionType) ->
                popupView.findViewById<ImageView>(viewId).setOnClickListener {
                    val oldReaction = this.currentReaction
                    if (oldReaction != reactionType) {
                        this.currentReaction = reactionType
                        updateReaction(postId, reactionType, oldReaction)
                    }
                    popupWindow.dismiss()
                }
            }

            val location = IntArray(2)
            likeIcon.getLocationOnScreen(location)
            popupWindow.showAsDropDown(likeIcon, 0, -likeIcon.height - popupView.measuredHeight - 16)
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
                likeIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.teal_200))
            } else {
                likeIcon.clearColorFilter()
            }
        }

        private fun updateReaction(postId: String, newReaction: String, oldReaction: String?) {
            val user = auth.currentUser ?: return
            updateLikeIcon(newReaction)
            val postRef = db.collection("posts").document(postId)
            val reactionRef = postRef.collection("reactions").document(user.uid)
            val userRef = db.collection("users").document(user.uid)

            db.runTransaction { transaction ->
                val post = transaction.get(postRef)
                val userDoc = transaction.get(userRef)
                val reactionCount = post.get("reactionCount") as? Map<String, Long> ?: emptyMap()
                val updatedCount = reactionCount.toMutableMap()

                if (oldReaction != null && oldReaction != newReaction) {
                    val currentOldCount = updatedCount[oldReaction] ?: 0L
                    if (currentOldCount > 0) {
                        updatedCount[oldReaction] = currentOldCount - 1
                    }
                }
                updatedCount[newReaction] = (updatedCount[newReaction] ?: 0L) + 1

                val firstName = userDoc.getString("firstName") ?: ""
                val lastName = userDoc.getString("lastName") ?: ""
                val userFullName = "$firstName $lastName".trim()

                transaction.set(reactionRef, mapOf("reactionType" to newReaction, "timestamp" to FieldValue.serverTimestamp(), "userId" to user.uid, "userFullName" to userFullName))
                transaction.update(postRef, "reactionCount", updatedCount)
                null
            }.addOnFailureListener {
                if (adapterPosition == RecyclerView.NO_POSITION) return@addOnFailureListener
                this.currentReaction = oldReaction
                updateLikeIcon(oldReaction)
                Log.e("PostViewHolder", "Failed to update reaction", it)
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
                    if (currentCount > 0) {
                        updatedCount[oldReaction] = currentCount - 1
                    }
                }

                transaction.delete(reactionRef)
                transaction.update(postRef, "reactionCount", updatedCount)
                null
            }.addOnFailureListener {
                if (adapterPosition == RecyclerView.NO_POSITION) return@addOnFailureListener
                this.currentReaction = oldReaction
                updateLikeIcon(oldReaction)
                Log.e("PostViewHolder", "Failed to remove reaction", it)
            }
        }
    }

    class ImagePagerAdapter(private val imageUrls: List<String>) :
        RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(android.R.id.icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val imageView = ImageView(parent.context).apply {
                id = android.R.id.icon
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return ViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            Glide.with(holder.imageView).load(imageUrls[position]).into(holder.imageView)
        }

        override fun getItemCount(): Int = imageUrls.size
    }
}
