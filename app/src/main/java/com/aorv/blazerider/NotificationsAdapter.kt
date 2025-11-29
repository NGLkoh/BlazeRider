package com.aorv.blazerider

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class NotificationsAdapter(
    private var notifications: List<Notification>,
    private val onItemClick: (Notification) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleTextView: TextView = view.findViewById(R.id.notification_title)
        private val bodyTextView: TextView = view.findViewById(R.id.notification_body)
        private val unreadDot: View = view.findViewById(R.id.unread_dot)

        fun bind(notification: Notification, clickListener: (Notification) -> Unit) {
            // Capitalize the first letter of the notification type for the title
            titleTextView.text = notification.type.replaceFirstChar { 
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() 
            }
            bodyTextView.text = notification.message

            // Set the background and unread dot based on the isRead status
            if (notification.isRead) {
                unreadDot.visibility = View.GONE
                itemView.setBackgroundColor(Color.WHITE)
            } else {
                unreadDot.visibility = View.VISIBLE
                itemView.setBackgroundColor(Color.parseColor("#FFF1F1")) // Light red for unread
            }
            
            // Set the click listener for the item
            itemView.setOnClickListener { clickListener(notification) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notifications, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(notifications[position], onItemClick)
    }

    override fun getItemCount() = notifications.size

    fun updateNotifications(newNotifications: List<Notification>) {
        notifications = newNotifications
        notifyDataSetChanged()
    }
}
