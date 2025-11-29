package com.aorv.blazerider

import android.app.Application
import android.app.Activity
import android.os.Bundle

class BlazeRiderApplication : Application() {

    var isMessagesActivityForeground = false
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity is MessagesActivity) {
                    isMessagesActivityForeground = true
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (activity is MessagesActivity) {
                    isMessagesActivityForeground = false
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}