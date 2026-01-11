package com.aorv.blazerider

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class NotificationsAdapter(
    private var notifications: List<DisplayNotification>,
    private val onItemClick: (DisplayNotification, Int) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleTextView: TextView = view.findViewById(R.id.notification_title)
        private val bodyTextView: TextView = view.findViewById(R.id.notification_body)
        private val timeTextView: TextView = view.findViewById(R.id.notification_time)
        private val unreadDot: View = view.findViewById(R.id.unread_dot)

        fun bind(displayNotification: DisplayNotification, position: Int, clickListener: (DisplayNotification, Int) -> Unit) {
            titleTextView.text = displayNotification.title
            bodyTextView.text = displayNotification.message
            
            val timestamp = displayNotification.original.createdAt
            if (timestamp != null) {
                val sdf = SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.getDefault())
                timeTextView.text = sdf.format(timestamp.toDate())
                timeTextView.visibility = View.VISIBLE
            } else {
                timeTextView.visibility = View.GONE
            }

            if (displayNotification.isRead) {
                unreadDot.visibility = View.GONE
                itemView.setBackgroundColor(Color.WHITE)
            } else {
                unreadDot.visibility = View.VISIBLE
                itemView.setBackgroundColor(Color.parseColor("#FFF1F1")) // Light red for unread
            }
            
            itemView.setOnClickListener { clickListener(displayNotification, position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notifications, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(notifications[position], position, onItemClick)
    }

    override fun getItemCount() = notifications.size

    fun updateNotifications(newNotifications: List<DisplayNotification>) {
        notifications = newNotifications
        notifyDataSetChanged()
    }
}
