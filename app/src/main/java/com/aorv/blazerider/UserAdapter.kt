package com.aorv.blazerider

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class UserAdapter(
    private var userList: List<UserRequest>,
    private val context: Context,
    private var currentTab: Int, // 0 = Pending, 1 = Accepted, 2 = Deleted
    private val onConfirmClick: (UserRequest) -> Unit,
    private val onDeactivateClick: (UserRequest) -> Unit, // Renamed for clarity (handles reject/remove)
    private val onReactivateClick: (UserRequest) -> Unit // New callback for reactivating
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = userList[position]

        // --- Set User Details ---
        holder.tvName.text = "${user.firstName.orEmpty()} ${user.lastName.orEmpty()}"
        holder.tvEmail.text = user.email.orEmpty()

        // Gender Display
        val genderSymbol = when {
            user.gender?.equals("Male", ignoreCase = true) == true -> "♂️"
            user.gender?.equals("Female", ignoreCase = true) == true -> "♀️"
            else -> "N/A"
        }
        holder.tvDetails.text = "Gender: $genderSymbol"

        // Status Display
        holder.tvStatus.text = when {
            user.deactivated -> "Status: Deactivated 🚫"
            user.isVerified -> "Status: Verified ✅"
            else -> "Status: Pending ⏳"
        }

        // --- Reason Display (Only for Deleted Tab) ---
        if (currentTab == 2) {
            holder.tvReason.visibility = View.VISIBLE
            holder.tvReason.text = "Reason: ${user.deactivationReason ?: "No reason provided"}"
        } else {
            holder.tvReason.visibility = View.GONE
        }

        // --- Button Visibility & Logic based on Tab ---
        when (currentTab) {
            0 -> { // PENDING TAB
                holder.btnConfirm.visibility = View.VISIBLE
                holder.btnConfirm.text = "Confirm"
                holder.btnConfirm.setOnClickListener {
                    showConfirmationDialog(holder.itemView.context, "Confirm Verification", "Are you sure you want to verify this user?") {
                        onConfirmClick(user)
                    }
                }

                holder.btnReject.visibility = View.VISIBLE
                holder.btnReject.text = "Reject"
                holder.btnReject.setOnClickListener {
                    // Use onDeactivateClick to trigger the reason dialog logic in Fragment
                    onDeactivateClick(user)
                }

                holder.btnReactivate.visibility = View.GONE
            }
            1 -> { // ACCEPTED TAB
                holder.btnConfirm.visibility = View.GONE // Already confirmed

                holder.btnReject.visibility = View.VISIBLE
                holder.btnReject.text = "Remove" // Change text to "Remove" for accepted users
                holder.btnReject.setOnClickListener {
                    onDeactivateClick(user)
                }

                holder.btnReactivate.visibility = View.GONE
            }
            2 -> { // DELETED TAB
                holder.btnConfirm.visibility = View.GONE
                holder.btnReject.visibility = View.GONE

                holder.btnReactivate.visibility = View.VISIBLE
                holder.btnReactivate.text = "Reactivate"
                holder.btnReactivate.setOnClickListener {
                    showConfirmationDialog(holder.itemView.context, "Reactivate User", "Are you sure you want to reactivate this user account?") {
                        onReactivateClick(user)
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = userList.size

    // Update list and tab state
    fun updateList(newList: List<UserRequest>, currentTab: Int) {
        this.userList = newList
        this.currentTab = currentTab
        notifyDataSetChanged()
    }

    // Helper for simple confirmation dialogs
    private fun showConfirmationDialog(context: Context, title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvName)
        val tvEmail: TextView = itemView.findViewById(R.id.tvEmail)
        val tvDetails: TextView = itemView.findViewById(R.id.tvDetails)
        val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        val tvReason: TextView = itemView.findViewById(R.id.tvReason) // Ensure this ID exists in item_user.xml

        val btnConfirm: Button = itemView.findViewById(R.id.btnConfirm)
        val btnReject: Button = itemView.findViewById(R.id.btnReject) // Used for Reject AND Remove
        val btnReactivate: Button = itemView.findViewById(R.id.btnReactivate) // Ensure this ID exists in item_user.xml
    }
}