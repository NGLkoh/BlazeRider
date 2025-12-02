package com.aorv.blazerider

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth

class JoinedUsersAdapter(
    private var users: List<JoinedUser>,
    private val onMessageClick: (JoinedUser) -> Unit
) : RecyclerView.Adapter<JoinedUsersAdapter.UserViewHolder>() {

    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_joined_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount(): Int = users.size

    fun updateUsers(newUsers: List<JoinedUser>) {
        this.users = newUsers
        notifyDataSetChanged()
    }

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userProfilePictureImageView: ImageView = itemView.findViewById(R.id.user_profile_picture)
        private val userNameTextView: TextView = itemView.findViewById(R.id.user_name)
        private val messageIcon: ImageView = itemView.findViewById(R.id.message_icon)

        fun bind(user: JoinedUser) {
            userNameTextView.text = user.name

            if (!user.profilePictureUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(user.profilePictureUrl)
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .circleCrop()
                    .into(userProfilePictureImageView)
            } else {
                userProfilePictureImageView.setImageResource(R.drawable.ic_profile)
            }

            if (user.userId == currentUserId) {
                messageIcon.visibility = View.GONE
            } else {
                messageIcon.visibility = View.VISIBLE
                messageIcon.setOnClickListener {
                    onMessageClick(user)
                }
            }
        }
    }
}
