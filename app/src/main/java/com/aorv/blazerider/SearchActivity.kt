package com.aorv.blazerider

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient

class SearchActivity : AppCompatActivity() {

    private lateinit var searchPlaceholder: EditText
    private lateinit var suggestionsRecyclerView: RecyclerView
    private lateinit var resultsContainer: CardView
    private lateinit var placesClient: PlacesClient
    private val suggestions = mutableListOf<AutocompletePrediction>()
    private val REQUEST_PERMISSION_CODE = 100
    private val SPEECH_REQUEST_CODE = 101
    private val TAG = "SearchActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        try {
            Places.initialize(applicationContext, "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE")
            placesClient = Places.createClient(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Places API")
            return
        }

        val backButton = findViewById<ImageView>(R.id.back_button)
        val micButton = findViewById<ImageView>(R.id.mic_button)
        searchPlaceholder = findViewById<EditText>(R.id.search_placeholder)
        suggestionsRecyclerView = findViewById<RecyclerView>(R.id.suggestions_recycler_view)
        resultsContainer = findViewById<CardView>(R.id.results_container)

        suggestionsRecyclerView.layoutManager = LinearLayoutManager(this)
        suggestionsRecyclerView.adapter = object : RecyclerView.Adapter<PlaceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
                val view = layoutInflater.inflate(R.layout.item_place_suggestion, parent, false)
                return PlaceViewHolder(view)
            }

            override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
                val prediction = suggestions[position]
                holder.placeName.text = prediction.getPrimaryText(null).toString()
                holder.placeAddress.text = prediction.getSecondaryText(null).toString()
                holder.itemView.setOnClickListener {
                    fetchPlaceDetails(prediction.placeId, prediction.getPrimaryText(null).toString())
                }
            }

            override fun getItemCount(): Int = suggestions.size
        }

        backButton.setOnClickListener { finish() }

        micButton.setOnClickListener {
            if (checkAudioPermission()) startSpeechToText() else requestAudioPermission()
        }

        searchPlaceholder.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.let { query ->
                    if (query.isNotEmpty()) fetchPlaceSuggestions(query)
                    else {
                        suggestions.clear()
                        updateUI()
                    }
                }
            }
        })

        intent.getStringExtra("SEARCH_QUERY")?.let { query ->
            searchPlaceholder.setText(query)
            fetchPlaceSuggestions(query)
        }
    }

    private fun updateUI() {
        if (suggestions.isNotEmpty()) {
            resultsContainer.visibility = View.VISIBLE
        } else {
            resultsContainer.visibility = View.GONE
        }
        suggestionsRecyclerView.adapter?.notifyDataSetChanged()
    }

    private fun checkAudioPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAudioPermission() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION_CODE)

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
        try { startActivityForResult(intent, SPEECH_REQUEST_CODE) } catch (e: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text ->
                searchPlaceholder.setText(text)
                fetchPlaceSuggestions(text)
            }
        }
    }

    private fun fetchPlaceSuggestions(query: String) {
        val token = AutocompleteSessionToken.newInstance()
        val request = FindAutocompletePredictionsRequest.builder()
            .setCountries("PH")
            .setSessionToken(token)
            .setQuery(query)
            .build()

        placesClient.findAutocompletePredictions(request).addOnSuccessListener { response ->
            suggestions.clear()
            suggestions.addAll(response.autocompletePredictions)
            updateUI()
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Error fetching suggestions")
        }
    }

    private fun fetchPlaceDetails(placeId: String, placeName: String) {
        val fields = listOf(com.google.android.libraries.places.api.model.Place.Field.NAME, com.google.android.libraries.places.api.model.Place.Field.ADDRESS, com.google.android.libraries.places.api.model.Place.Field.LAT_LNG)
        val request = FetchPlaceRequest.builder(placeId, fields).build()

        placesClient.fetchPlace(request).addOnSuccessListener { response ->
            val place = response.place
            val resultIntent = Intent().apply {
                putExtra("SEARCH_QUERY", place.name)
                putExtra("PLACE_ADDRESS", place.address)
                putExtra("PLACE_LAT", place.latLng?.latitude ?: 0.0)
                putExtra("PLACE_LNG", place.latLng?.longitude ?: 0.0)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    inner class PlaceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val placeName: TextView = itemView.findViewById(android.R.id.text1)
        val placeAddress: TextView = itemView.findViewById(android.R.id.text2)
    }
}