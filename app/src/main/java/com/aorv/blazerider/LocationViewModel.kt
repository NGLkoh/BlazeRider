package com.aorv.blazerider

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.google.android.gms.maps.model.LatLng

class LocationViewModel : ViewModel() {
    private val _lastKnownLocation = MutableLiveData<LatLng?>(null)
    val lastKnownLocation: LiveData<LatLng?> get() = _lastKnownLocation

    fun updateLocation(location: LatLng?) {
        _lastKnownLocation.value = location
    }
}