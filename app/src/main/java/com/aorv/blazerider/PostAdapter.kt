package com.aorv.blazerider

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class PostAdapter(
    private val onDeletePost: (Post) -> Unit,
    private val onCommentClick: (Post) -> Unit,
    private val isAnnouncement: Boolean = false
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

    inner class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profilePic = itemView.findViewById<ShapeableImageView>(R.id.post_profile_pic)
        private val userName = itemView.findViewById<TextView>(R.id.post_user_name)
        private val timestamp = itemView.findViewById<TextView>(R.id.post_timestamp)
        private val content = itemView.findViewById<TextView>(R.id.post_content)
        private val imageContainer = itemView.findViewById<ConstraintLayout>(R.id.post_image_container)
        private val imageViewPager = itemView.findViewById<ViewPager2>(R.id.post_image_view_pager)
        private val imageCounter = itemView.findViewById<TextView>(R.id.post_image_counter)
        
        private val likeContainer = itemView.findViewById<LinearLayout>(R.id.like_container)
        private val likeIcon = itemView.findViewById<ImageView>(R.id.post_like_icon)
        private val likeText = itemView.findViewById<TextView>(R.id.post_like_text)
        
        private val commentContainer = itemView.findViewById<LinearLayout>(R.id.comment_container)
        private val reactionsCount = itemView.findViewById<TextView>(R.id.post_reactions_count)
        private val commentsCount = itemView.findViewById<TextView>(R.id.post_comments_count)
        private val adminBadge = itemView.findViewById<TextView>(R.id.post_admin_badge)
        private val postOptionsIcon = itemView.findViewById<ImageView>(R.id.post_options_icon)

        private val rideControlsContainer = itemView.findViewById<LinearLayout>(R.id.ride_controls_container)
        private val btnStartRoute = itemView.findViewById<MaterialButton>(R.id.btnPostStartRoute)
        private val btnArrived = itemView.findViewById<MaterialButton>(R.id.btnPostArrived)
        private val btnCancel = itemView.findViewById<MaterialButton>(R.id.btnPostCancel)

        private val db = FirebaseFirestore.getInstance()
        private val auth = FirebaseAuth.getInstance()
        
        private var currentReaction: String? = null
        private var boundPost: Post? = null

        fun bind(post: Post) {
            boundPost = post
            currentReaction = null
            updateLikeIcon(null)

            db.collection("users").document(post.userId).get().addOnSuccessListener { document ->
                if (document.exists()) {
                    userName.text = "${document.getString("firstName")} ${document.getString("lastName")}"
                    Glide.with(itemView.context).load(document.getString("profileImageUrl"))
                        .placeholder(R.drawable.ic_anonymous).into(profilePic)
                }
            }

            content.text = post.content
            timestamp.text = post.createdAt?.let { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(it) } ?: ""
            adminBadge.visibility = if (post.admin) View.VISIBLE else View.GONE

            val isMyPost = post.userId == auth.currentUser?.uid
            
            // Show options (like delete) only if it's my post
            if (isMyPost) {
                postOptionsIcon.visibility = View.VISIBLE
                postOptionsIcon.setOnClickListener { showPostMenu(it, post) }
            } else {
                postOptionsIcon.visibility = View.GONE
            }

            val isRideEvent = post.type == "ride_event" || post.content.contains("NEW RIDE ALERT")
            
            if (post.admin && isMyPost && isRideEvent && post.sharedRouteId.isNotEmpty()) {
                rideControlsContainer.visibility = View.VISIBLE
                setupRideControls(post)
            } else {
                rideControlsContainer.visibility = View.GONE
            }

            setupImages(post)
            setupReactions(post)
            
            commentContainer.setOnClickListener { onCommentClick(post) }
            
            likeContainer.setOnClickListener { handleLikeClick() }
            likeContainer.setOnLongClickListener {
                showReactionPicker()
                true
            }
            
            // NEW: Click listener for reactions count
            reactionsCount.setOnClickListener {
                if (post.reactionCount.values.sum() > 0) {
                    showReactionsDialog(post.id)
                }
            }

            auth.currentUser?.uid?.let { uid ->
                db.collection("posts").document(post.id).collection("reactions").document(uid).get()
                    .addOnSuccessListener { doc ->
                        if (boundPost?.id == post.id) {
                            currentReaction = doc.getString("reactionType")
                            updateLikeIcon(currentReaction)
                        }
                    }
            }
        }

        private fun showPostMenu(view: View, post: Post) {
            val popup = PopupMenu(itemView.context, view)
            popup.menu.add("Delete")
            popup.setOnMenuItemClickListener { item ->
                if (item.title == "Delete") {
                    showDeleteConfirmation(post)
                }
                true
            }
            popup.show()
        }

        private fun showDeleteConfirmation(post: Post) {
            AlertDialog.Builder(itemView.context)
                .setTitle("Delete Post")
                .setMessage("Are you sure you want to delete this post? This action cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    onDeletePost(post)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun handleLikeClick() {
            val post = boundPost ?: return
            val oldReaction = currentReaction
            if (oldReaction == "like") {
                currentReaction = null
                removeReaction(post.id, "like")
            } else {
                currentReaction = "like"
                updateReaction(post.id, "like", oldReaction)
            }
        }

        private fun showReactionPicker() {
            val post = boundPost ?: return
            val inflater = LayoutInflater.from(itemView.context)
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
                        updateReaction(post.id, reactionType, oldReaction)
                    }
                    popupWindow.dismiss()
                }
            }

            popupWindow.elevation = 10f
            popupWindow.showAsDropDown(likeContainer, 0, -likeContainer.height - 280)
        }

        private fun showReactionsDialog(postId: String) {
            val context = itemView.context as? FragmentActivity ?: return
            val dialog = ShowReactionsDialogFragment.newInstance(postId)
            dialog.show(context.supportFragmentManager, "ReactionsDialog")
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
                likeIcon.setColorFilter(ContextCompat.getColor(itemView.context, R.color.dark_gray))
                likeText.setTextColor(ContextCompat.getColor(itemView.context, R.color.dark_gray))
            } else {
                likeIcon.clearColorFilter()
                val color = if (reactionType == "like") R.color.blue else R.color.red_orange
                likeText.setTextColor(ContextCompat.getColor(itemView.context, color))
            }
        }

        private fun updateReaction(postId: String, newReaction: String, oldReaction: String?) {
            val user = auth.currentUser ?: return
            updateLikeIcon(newReaction)
            val postRef = db.collection("posts").document(postId)
            val reactionRef = postRef.collection("reactions").document(user.uid)

            db.runTransaction { transaction ->
                val postSnapshot = transaction.get(postRef)
                val reactionCount = postSnapshot.get("reactionCount") as? Map<String, Any> ?: emptyMap()
                val updatedCount = reactionCount.toMutableMap()

                if (oldReaction != null && oldReaction != newReaction) {
                    val currentOldCount = (updatedCount[oldReaction] as? Long) ?: 0L
                    if (currentOldCount > 0) updatedCount[oldReaction] = currentOldCount - 1
                }
                updatedCount[newReaction] = ((updatedCount[newReaction] as? Long) ?: 0L) + 1

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
                val postSnapshot = transaction.get(postRef)
                val reactionCount = postSnapshot.get("reactionCount") as? Map<String, Any> ?: emptyMap()
                val updatedCount = reactionCount.toMutableMap()

                if (oldReaction != null) {
                    val currentCount = (updatedCount[oldReaction] as? Long) ?: 0L
                    if (currentCount > 0) updatedCount[oldReaction] = currentCount - 1
                }

                transaction.delete(reactionRef)
                transaction.update(postRef, "reactionCount", updatedCount)
                null
            }
        }

        private fun setupRideControls(post: Post) {
            db.collection("sharedRoutes").document(post.sharedRouteId)
                .addSnapshotListener { snapshot, _ ->
                    if (snapshot == null || !snapshot.exists()) {
                        rideControlsContainer.visibility = View.GONE
                        return@addSnapshotListener
                    }
                    val status = snapshot.getString("status") ?: "active"
                    if (status == "completed" || status == "cancelled") {
                        rideControlsContainer.visibility = View.GONE
                        return@addSnapshotListener
                    }
                    rideControlsContainer.visibility = View.VISIBLE
                    if (status == "ongoing") {
                        btnArrived.visibility = View.VISIBLE
                        btnStartRoute.text = "Continue Route"
                    } else {
                        btnArrived.visibility = View.GONE
                        btnStartRoute.text = "Start Route"
                    }
                    btnStartRoute.setOnClickListener {
                        if (status != "ongoing") {
                            db.collection("sharedRoutes").document(post.sharedRouteId).update("status", "ongoing")
                        }
                        openNavigation(snapshot.get("destinationCoordinates") as? Map<String, Double>)
                    }
                    btnArrived.setOnClickListener {
                        showConfirmation("Arrived", "Have you reached the destination?") {
                            db.collection("sharedRoutes").document(post.sharedRouteId).update("status", "completed")
                        }
                    }
                    btnCancel.setOnClickListener {
                        showConfirmation("Cancel Ride", "Are you sure you want to cancel this ride event?") {
                            db.collection("sharedRoutes").document(post.sharedRouteId).update("status", "cancelled")
                        }
                    }
                }
        }

        private fun openNavigation(coords: Map<String, Double>?) {
            val lat = coords?.get("latitude") ?: return
            val lng = coords["longitude"] ?: return
            val uri = Uri.parse("google.navigation:q=$lat,$lng")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            itemView.context.startActivity(intent)
        }

        private fun showConfirmation(title: String, message: String, onConfirm: () -> Unit) {
            AlertDialog.Builder(itemView.context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes") { _, _ -> onConfirm() }
                .setNegativeButton("No", null)
                .show()
        }

        private fun setupImages(post: Post) {
            if (post.imageUris.isNotEmpty()) {
                imageContainer.visibility = View.VISIBLE
                imageViewPager.adapter = ImagePagerAdapter(post.imageUris)
            } else {
                imageContainer.visibility = View.GONE
            }
        }

        private fun setupReactions(post: Post) {
            val total = post.reactionCount.values.sum()
            reactionsCount.text = if (total > 0) "$total reactions" else ""
            commentsCount.text = if (post.commentsCount > 0) "${post.commentsCount} comments" else ""
        }
    }

    class ImagePagerAdapter(private val urls: List<String>) : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {
        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) { val img = v as ImageView }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(ImageView(p.context).apply { 
            layoutParams = ViewGroup.LayoutParams(-1, -1); scaleType = ImageView.ScaleType.CENTER_CROP 
        })
        override fun onBindViewHolder(h: ViewHolder, p: Int) { Glide.with(h.img).load(urls[p]).into(h.img) }
        override fun getItemCount() = urls.size
    }
}