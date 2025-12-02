package com.aorv.blazerider

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.*

class CommentsActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var recyclerView: RecyclerView
    private lateinit var commentsAdapter: CommentsAdapter
    private var commentAdded = false

    companion object {
        const val COMMENT_ADDED = "comment_added"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(R.layout.activity_comments)

        val toolbar = findViewById<Toolbar>(R.id.comments_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        overridePendingTransition(R.anim.slide_up, R.anim.stay)

        findViewById<ImageButton>(R.id.comments_close_button).setOnClickListener {
            finish()
        }

        val profilePic = findViewById<ShapeableImageView>(R.id.comment_profile_pic)
        loadCurrentUserProfileImage(profilePic)

        val postId = intent.getStringExtra("POST_ID") ?: return

        recyclerView = findViewById(R.id.comment_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        commentsAdapter = CommentsAdapter(postId)
        recyclerView.adapter = commentsAdapter

        loadComments(postId)

        val commentInput = findViewById<EditText>(R.id.comment_input)
        val submitButton = findViewById<ImageButton>(R.id.comment_submit_button)

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
                                        imageUris = emptyList(),
                                        reactionCount = doc.get("reactionCount") as? Map<String, Long> ?: emptyMap()
                                    )
                                    comments.add(comment)
                                    comments.sortBy { it.createdAt }
                                    commentsAdapter.submitComments(comments)
                                    recyclerView.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
                                    noCommentsLayout.visibility = if (comments.isEmpty()) View.VISIBLE else View.GONE
                                }
                            }
                    }
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
            "reactionCount" to emptyMap<String, Long>()
        )

        db.collection("posts").document(postId)
            .collection("comments")
            .add(comment)
            .addOnSuccessListener {
                db.collection("posts").document(postId).update("commentsCount", FieldValue.increment(1))
                commentAdded = true
                Toast.makeText(this@CommentsActivity, "Comment submitted", Toast.LENGTH_SHORT).show()
            }.addOnFailureListener {
                Toast.makeText(this@CommentsActivity, "Failed to submit comment", Toast.LENGTH_SHORT).show()
            }
    }

    fun deleteComment(postId: String, commentId: String) {
        db.collection("posts").document(postId)
            .collection("comments").document(commentId)
            .delete()
            .addOnSuccessListener {
                db.collection("posts").document(postId).update("commentsCount", FieldValue.increment(-1))
                commentAdded = true
            }
    }

    override fun finish() {
        if (commentAdded) {
            val resultIntent = Intent()
            resultIntent.putExtra(COMMENT_ADDED, true)
            setResult(Activity.RESULT_OK, resultIntent)
        }
        super.finish()
        overridePendingTransition(R.anim.stay, R.anim.slide_down)
    }

    inner class CommentsAdapter(private val postId: String) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

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
            private val moreButton = itemView.findViewById<ImageButton>(R.id.comment_more_button)

            fun bind(comment: Comment) {
                if (comment.profileImageUrl.isNotEmpty()) {
                    Glide.with(itemView.context)
                        .load(comment.profileImageUrl)
                        .placeholder(R.drawable.ic_anonymous)
                        .error(R.drawable.ic_anonymous)
                        .into(profilePic)
                } else {
                    profilePic.setImageResource(R.drawable.ic_anonymous)
                }

                userName.text = "${comment.firstName} ${comment.lastName}".trim()

                timestamp.text = comment.createdAt?.let {
                    SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault()).format(it)
                } ?: ""

                if (comment.content.isNotEmpty()) {
                    content.visibility = View.VISIBLE
                    content.text = comment.content
                } else {
                    content.visibility = View.GONE
                }

                val currentUser = auth.currentUser
                if (currentUser != null && currentUser.uid == comment.userId) {
                    moreButton.visibility = View.VISIBLE
                    moreButton.setOnClickListener { view ->
                        val popup = PopupMenu(view.context, view)
                        popup.menu.add("Delete")
                        popup.setOnMenuItemClickListener { menuItem ->
                            when (menuItem.title) {
                                "Delete" -> {
                                    deleteComment(postId, comment.id)
                                    true
                                }
                                else -> false
                            }
                        }
                        popup.show()
                    }
                } else {
                    moreButton.visibility = View.GONE
                }
            }
        }
    }
}
