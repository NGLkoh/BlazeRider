package com.aorv.blazerider

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot

class ShowReactionsDialogFragment : DialogFragment() {

    private var postId: String? = null
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "ReactionDialog"

    companion object {
        private const val ARG_POST_ID = "postId"
        fun newInstance(postId: String): ShowReactionsDialogFragment {
            val fragment = ShowReactionsDialogFragment()
            val args = Bundle()
            args.putString(ARG_POST_ID, postId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        postId = arguments?.getString(ARG_POST_ID)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { activity ->
            val builder = AlertDialog.Builder(activity)
            val inflater = requireActivity().layoutInflater
            val view = inflater.inflate(R.layout.dialog_reactions, null)

            builder.setView(view)
                .setTitle("Post Reactions")
                .setPositiveButton("Close") { dialog, _ ->
                    dialog.cancel()
                }

            val reactionListContainer = view.findViewById<LinearLayout>(R.id.reaction_list_container)

            if (postId != null) {
                fetchAndDisplayReactions(postId!!, reactionListContainer)
            }

            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun fetchAndDisplayReactions(postId: String, container: LinearLayout) {
        val reactionsRef = db.collection("posts").document(postId).collection("reactions")

        reactionsRef.get()
            .addOnSuccessListener { result ->
                container.removeAllViews()

                if (result.isEmpty) {
                    return@addOnSuccessListener
                }

                for (document: QueryDocumentSnapshot in result) {
                    val userId = document.id
                    val reactionType = document.getString("reactionType") ?: "like" // Default to 'like'

                    displayUserReaction(userId, reactionType, container)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error getting reactions: ", e)
            }
    }

    private fun displayUserReaction(userId: String, reactionType: String, container: LinearLayout) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { userDoc ->
                if (userDoc.exists()) {
                    val firstName = userDoc.getString("firstName") ?: "Anonymous"
                    val lastName = userDoc.getString("lastName") ?: ""
                    val fullName = if (lastName.isNotEmpty()) "$firstName $lastName" else firstName
                    val profileImageUrl = userDoc.getString("profileImageUrl")

                    val reactionIconRes = getReactionIconRes(reactionType)

                    val layoutInflater = LayoutInflater.from(context)
                    // Note: Ensure item_reaction_user.xml exists in your layout folder
                    val userView = layoutInflater.inflate(R.layout.item_reaction_user, null)

                    userView.findViewById<TextView>(R.id.reaction_user_name).text = fullName
                    userView.findViewById<ImageView>(R.id.reaction_icon).setImageResource(reactionIconRes)

                    val profilePic = userView.findViewById<ShapeableImageView>(R.id.reaction_user_profile_pic)
                    if (!profileImageUrl.isNullOrEmpty()) {
                        Glide.with(this).load(profileImageUrl)
                            .placeholder(R.drawable.ic_anonymous).into(profilePic)
                    } else {
                        profilePic.setImageResource(R.drawable.ic_anonymous)
                    }

                    container.addView(userView)
                }
            }
    }

    private fun getReactionIconRes(reactionType: String): Int {
        return when (reactionType) {
            "like" -> R.drawable.ic_fb_like
            "love" -> R.drawable.ic_fb_love
            "haha" -> R.drawable.ic_fb_laugh
            "wow" -> R.drawable.ic_fb_wow
            "sad" -> R.drawable.ic_fb_sad
            "angry" -> R.drawable.ic_fb_angry
            else -> R.drawable.ic_fb_like
        }
    }
}