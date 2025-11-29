package com.aorv.blazerider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NotificationsFragment : Fragment() {

    private lateinit var notificationViewModel: NotificationViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.activity_notifications, container, false)
        val recyclerView = view.findViewById<RecyclerView>(R.id.notifications_recyclerview)
        val adapter = NotificationsAdapter(emptyList()) // Start with an empty list
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        val notificationDao = AppDatabase.getDatabase(requireContext()).notificationDao()
        val factory = NotificationViewModelFactory(notificationDao)
        notificationViewModel = ViewModelProvider(this, factory).get(NotificationViewModel::class.java)

        notificationViewModel.allNotifications.observe(viewLifecycleOwner) {
                notifications -> adapter.updateNotifications(notifications)
        }

        return view
    }
}
