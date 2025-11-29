package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.Request
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject

class CurrentAddressActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var stateDropdown: AutoCompleteTextView
    private lateinit var cityDropdown: AutoCompleteTextView
    private lateinit var barangayDropdown: AutoCompleteTextView
    private lateinit var houseNoEditText: TextInputEditText
    private lateinit var btnNext: Button

    private val provinces = mutableListOf<Pair<String, String>>() // name, code
    private val cities = mutableListOf<Pair<String, String>>() // name, code
    private val barangays = mutableListOf<String>()
    private var selectedProvinceCode: String? = null
    private var selectedCityCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_current_address)

        // Ensure status bar and navigation bar are visible
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Initialize UI elements
        stateDropdown = findViewById(R.id.state)
        cityDropdown = findViewById(R.id.city)
        barangayDropdown = findViewById(R.id.barangay)
        houseNoEditText = findViewById(R.id.houseNo)
        btnNext = findViewById(R.id.btnNext)

        // Disable city and barangay dropdowns initially
        cityDropdown.isEnabled = false
        barangayDropdown.isEnabled = false

        // Check if user is logged in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "No user logged in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SignUpActivity::class.java))
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            finish()
            return
        }

        // Fetch provinces
        fetchProvinces()

        // Province dropdown listener
        stateDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedProvinceCode = provinces[position].second
            cityDropdown.isEnabled = true
            cities.clear()
            barangays.clear()
            cityDropdown.setText("")
            barangayDropdown.setText("")
            barangayDropdown.isEnabled = false
            fetchCities(selectedProvinceCode!!)
        }

        // City dropdown listener
        cityDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedCityCode = cities[position].second
            barangayDropdown.isEnabled = true
            barangays.clear()
            barangayDropdown.setText("")
            fetchBarangays(selectedProvinceCode!!, selectedCityCode!!)
        }

        // Next button
        btnNext.setOnClickListener {
            val state = stateDropdown.text.toString()
            val city = cityDropdown.text.toString()
            val barangay = barangayDropdown.text.toString()
            val houseNo = houseNoEditText.text.toString()

            if (state.isEmpty() || city.isEmpty() || barangay.isEmpty() || houseNo.isEmpty()) {
                Toast.makeText(this, "Please fill out all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save address to Firestore
            val addressData = mapOf(
                "province" to state,
                "city" to city,
                "barangay" to barangay,
                "houseNo" to houseNo,
                "stepCompleted" to 3
            )

            db.collection("users").document(currentUser.uid).update(addressData)
                .addOnSuccessListener {
                    // Update Realtime Database status
                    val status = mapOf(
                        "state" to "online",
                        "last_changed" to System.currentTimeMillis()
                    )
                    FirebaseDatabase.getInstance().getReference("status").child(currentUser.uid)
                        .setValue(status)

                    Toast.makeText(this, "Address saved", Toast.LENGTH_SHORT).show()
                    // Proceed to next activity (placeholder)
                    startActivity(Intent(this, AdminApprovalActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    finish()
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to save address", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun fetchProvinces() {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces"

        val jsonArrayRequest = JsonArrayRequest(
            Request.Method.GET, url, null,
            { response ->
                provinces.clear()
                for (i in 0 until response.length()) {
                    val province = response.getJSONObject(i)
                    val name = province.getString("name")
                    val code = province.getString("code")
                    provinces.add(name to code)
                }
                val provinceNames = provinces.map { it.first }
                stateDropdown.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, provinceNames)
                )
            },
            { error ->
                Toast.makeText(this, "Failed to fetch provinces: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        )

        queue.add(jsonArrayRequest)
    }

    private fun fetchCities(provinceCode: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces/$provinceCode/cities-municipalities"

        val jsonArrayRequest = JsonArrayRequest(
            Request.Method.GET, url, null,
            { response ->
                cities.clear()
                for (i in 0 until response.length()) {
                    val city = response.getJSONObject(i)
                    val name = city.getString("name")
                    val code = city.getString("code")
                    cities.add(name to code)
                }
                val cityNames = cities.map { it.first }
                cityDropdown.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cityNames)
                )
            },
            { error ->
                Toast.makeText(this, "Failed to fetch cities: ${error.message}", Toast.LENGTH_SHORT).show()
                cityDropdown.isEnabled = false
            }
        )

        queue.add(jsonArrayRequest)
    }

    private fun fetchBarangays(provinceCode: String, cityCode: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces/$provinceCode/barangays"

        val jsonArrayRequest = JsonArrayRequest(
            Request.Method.GET, url, null,
            { response ->
                barangays.clear()
                for (i in 0 until response.length()) {
                    val barangay = response.getJSONObject(i)
                    val cityCodeFromJson = barangay.optString("cityCode", "")
                    val municipalityCode = barangay.optString("municipalityCode", "")
                    if (cityCodeFromJson == cityCode || municipalityCode == cityCode) {
                        val name = barangay.getString("name")
                        barangays.add(name)
                    }
                }
                barangayDropdown.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, barangays)
                )
            },
            { error ->
                Toast.makeText(this, "Failed to fetch barangays: ${error.message}", Toast.LENGTH_SHORT).show()
                barangayDropdown.isEnabled = false
            }
        )

        queue.add(jsonArrayRequest)
    }
}