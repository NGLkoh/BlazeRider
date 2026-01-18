package com.aorv.blazerider

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore

class UserAdapter(
    private var userList: MutableList<UserRequest>,
    private val context: Context,
    private var showPending: Boolean,
    private val onConfirmClick: (UserRequest) -> Unit,
    private val onRejectClick: (UserRequest) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = userList[position]

        // Set user details
        holder.tvName.text = "${user.firstName.orEmpty()} ${user.lastName.orEmpty()}"
        holder.tvEmail.text = user.email.orEmpty()
        holder.tvDetails.text = "Gender: ${when {
            user.gender?.equals("Male", ignoreCase = true) == true -> "♂️"
            user.gender?.equals("Female", ignoreCase = true) == true -> "♀️"
            else -> "N/A"
        }}"
        holder.tvStatus.text = "Verified: ${if (user.isVerified) "✅" else "❌"}"

        // Update button state based on verification status and showPending
        holder.btnConfirm.apply {
            isEnabled = !user.isVerified && showPending
            text = if (user.isVerified) "Confirmed" else "Confirm"
            visibility = if (user.isVerified && !showPending) View.GONE else View.VISIBLE
        }
        
        holder.btnReject.apply {
            visibility = if (showPending && !user.isVerified) View.VISIBLE else View.GONE
        }

        // Handle confirm button click
        holder.btnConfirm.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Confirm Verification")
                .setMessage("Are you sure you want to verify this user?")
                .setPositiveButton("Yes") { _, _ ->
                    onConfirmClick(user)
                }
                .setNegativeButton("No") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        // Handle reject button click
        holder.btnReject.setOnClickListener {
            AlertDialog.Builder(holder.itemView.context)
                .setTitle("Reject User")
                .setMessage("Are you sure you want to reject and remove this user from the database? This action cannot be undone.")
                .setPositiveButton("Reject") { _, _ ->
                    onRejectClick(user)
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    override fun getItemCount(): Int = userList.size

    fun updateList(newList: List<UserRequest>, showPending: Boolean) {
        userList = newList.toMutableList()
        this.showPending = showPending
        notifyDataSetChanged()
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvEmail: TextView = itemView.findViewById(R.id.tvEmail)
        val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)
        val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        val btnConfirm: Button = itemView.findViewById(R.id.btnConfirm)
        val btnReject: Button = itemView.findViewById(R.id.btnReject)
    }
}