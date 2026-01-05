package com.aorv.blazerider

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ReactionsAdapter(private val reactions: List<Reaction>) : RecyclerView.Adapter<ReactionsAdapter.ReactionViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReactionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reaction, parent, false)
        return ReactionViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReactionViewHolder, position: Int) {
        holder.bind(reactions[position])
    }

    override fun getItemCount() = reactions.size

    class ReactionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userFullName: TextView = itemView.findViewById(R.id.user_full_name)
        private val profilePicture: ImageView = itemView.findViewById(R.id.profile_picture)
        private val reactionIcon: ImageView = itemView.findViewById(R.id.reaction_icon)

        fun bind(reaction: Reaction) {
            userFullName.text = reaction.userFullName
            Glide.with(itemView.context)
                .load(reaction.userProfilePictureUrl)
                .into(profilePicture)

            val reactionType = reaction.reactionType
            val iconRes = when (reactionType) {
                "like" -> R.drawable.ic_fb_like
                "love" -> R.drawable.ic_fb_love
                "haha" -> R.drawable.ic_fb_laugh
                "wow" -> R.drawable.ic_fb_wow
                "sad" -> R.drawable.ic_fb_sad
                "angry" -> R.drawable.ic_fb_angry
                else -> 0
            }
            if (iconRes != 0) {
                reactionIcon.setImageResource(iconRes)
                reactionIcon.visibility = View.VISIBLE
            } else {
                reactionIcon.visibility = View.GONE
            }
        }
    }
}
