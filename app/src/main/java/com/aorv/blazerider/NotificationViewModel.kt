package com.aorv.blazerider

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class NotificationViewModel(private val notificationDao: NotificationDao) : ViewModel() {

    val allNotifications: LiveData<List<NotificationEntity>> = notificationDao.getAllNotifications()
}

class NotificationViewModelFactory(private val notificationDao: NotificationDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NotificationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NotificationViewModel(notificationDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
