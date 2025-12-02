package com.aorv.blazerider

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.volley.Request
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditProfileActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var provinceDropdown: AutoCompleteTextView
    private lateinit var cityDropdown: AutoCompleteTextView
    private lateinit var barangayDropdown: AutoCompleteTextView
    private var profileImageUri: Uri? = null
    private val PICK_IMAGE_REQUEST = 1
    private val provinces = mutableListOf<Pair<String, String>>() // name, code
    private val cities = mutableListOf<Pair<String, String>>() // name, code
    private val barangays = mutableListOf<String>()
    private var selectedProvinceCode: String? = null
    private var selectedCityCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        // Disable edge-to-edge and set status bar color
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = resources.getColor(R.color.red_orange, theme)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        // UI elements
        val profileImage = findViewById<ShapeableImageView>(R.id.profile_image)
        val cameraIcon = findViewById<ImageView>(R.id.camera_icon)
        val firstName = findViewById<EditText>(R.id.firstName)
        val lastName = findViewById<EditText>(R.id.lastName)
        val email = findViewById<EditText>(R.id.email)
        val birthdate = findViewById<EditText>(R.id.birthdate)
        val gender = findViewById<AutoCompleteTextView>(R.id.gender)
        provinceDropdown = findViewById<AutoCompleteTextView>(R.id.province)
        cityDropdown = findViewById<AutoCompleteTextView>(R.id.city)
        barangayDropdown = findViewById<AutoCompleteTextView>(R.id.barangay)
        val address = findViewById<EditText>(R.id.address)
        val btnSaveChanges = findViewById<Button>(R.id.btnSaveChanges)
        val scrollView = findViewById<ScrollView>(R.id.scrollView)

        // Apply system bar insets to ScrollView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, 16, v.paddingRight, systemBars.bottom + 32)
            insets
        }

        // Back button
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            finish()
        }

        // Gender dropdown
        val genderOptions = listOf("Male", "Female", "Prefer not to say")
        gender.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, genderOptions))

        // Disable city and barangay dropdowns initially
        cityDropdown.isEnabled = false
        barangayDropdown.isEnabled = false

        // Fetch provinces
        fetchProvinces()

        // Province dropdown listener
        provinceDropdown.setOnItemClickListener { _, _, position, _ ->
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

        // Birthdate picker
        birthdate.setOnClickListener {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH)
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            val datePicker = DatePickerDialog(
                this,
                { _, selectedYear, selectedMonth, selectedDay ->
                    val formatted = String.format("%02d/%02d/%04d", selectedDay, selectedMonth + 1, selectedYear)
                    birthdate.setText(formatted)
                },
                year, month, day
            )
            datePicker.datePicker.maxDate = System.currentTimeMillis()
            datePicker.show()
        }

        // Profile image selection
        cameraIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        // Smooth scrolling for input fields
        val fields = listOf(firstName, lastName, birthdate, gender, provinceDropdown, cityDropdown, barangayDropdown, address)
        fields.forEach { field ->
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scrollView.postDelayed({
                        val rect = Rect()
                        field.getGlobalVisibleRect(rect)
                        scrollView.smoothScrollTo(0, rect.top - 100)
                    }, 300)
                }
            }
        }

        // Load existing user data
        val user = auth.currentUser
        if (user != null) {
            email.setText(user.email)
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        firstName.setText(document.getString("firstName"))
                        lastName.setText(document.getString("lastName"))
                        document.getTimestamp("birthdate")?.let { timestamp ->
                            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                            birthdate.setText(dateFormat.format(timestamp.toDate()))
                        }
                        gender.setText(document.getString("gender"), false)
                        val province = document.getString("province")
                        val city = document.getString("city")
                        val barangay = document.getString("barangay")
                        address.setText(document.getString("address"))
                        document.getString("profileImageUrl")?.let { url ->
                            if (url.isNotEmpty()) {
                                Glide.with(this@EditProfileActivity)
                                    .load(url)
                                    .placeholder(R.drawable.ic_blank)
                                    .error(R.drawable.ic_blank)
                                    .into(profileImage)
                            } else {
                                profileImage.setImageResource(R.drawable.ic_blank)
                            }
                        } ?: profileImage.setImageResource(R.drawable.ic_blank)
                        province?.let {
                            provinceDropdown.setText(it, false)
                            selectedProvinceCode = provinces.find { p -> p.first == it }?.second
                            if (selectedProvinceCode != null) {
                                fetchCities(selectedProvinceCode!!)
                                city?.let {
                                    cityDropdown.setText(it, false)
                                    selectedCityCode = cities.find { c -> c.first == it }?.second
                                    if (selectedCityCode != null) {
                                        fetchBarangays(selectedProvinceCode!!, selectedCityCode!!)
                                        barangay?.let {
                                            barangayDropdown.setText(it, false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to load user data.", Toast.LENGTH_SHORT).show()
                }
        } else {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Save changes
        btnSaveChanges.setOnClickListener {
            when {
                firstName.text.isEmpty() -> {
                    firstName.error = "First name is required"
                    firstName.requestFocus()
                    return@setOnClickListener
                }
                lastName.text.isEmpty() -> {
                    lastName.error = "Last name is required"
                    lastName.requestFocus()
                    return@setOnClickListener
                }
                birthdate.text.isEmpty() -> {
                    birthdate.error = "Birthdate is required"
                    birthdate.requestFocus()
                    return@setOnClickListener
                }
                gender.text.isEmpty() -> {
                    gender.error = "Gender is required"
                    gender.requestFocus()
                    return@setOnClickListener
                }
                provinceDropdown.text.isEmpty() -> {
                    provinceDropdown.error = "Province is required"
                    provinceDropdown.requestFocus()
                    return@setOnClickListener
                }
                cityDropdown.text.isEmpty() -> {
                    cityDropdown.error = "City is required"
                    cityDropdown.requestFocus()
                    return@setOnClickListener
                }
                barangayDropdown.text.isEmpty() -> {
                    barangayDropdown.error = "Barangay is required"
                    barangayDropdown.requestFocus()
                    return@setOnClickListener
                }
                address.text.isEmpty() -> {
                    address.error = "Address is required"
                    address.requestFocus()
                    return@setOnClickListener
                }
                else -> {
                    val user = auth.currentUser
                    if (user != null) {
                        // Parse birthdate to Timestamp
                        val birthdateString = birthdate.text.toString()
                        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        dateFormat.isLenient = false
                        val date = dateFormat.parse(birthdateString)
                        val calendar = Calendar.getInstance().apply {
                            time = date
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val birthdateTimestamp = com.google.firebase.Timestamp(calendar.time)

                        val userData = mutableMapOf<String, Any>(
                            "firstName" to firstName.text.toString(),
                            "lastName" to lastName.text.toString(),
                            "birthdate" to birthdateTimestamp,
                            "gender" to gender.text.toString(),
                            "province" to provinceDropdown.text.toString(),
                            "city" to cityDropdown.text.toString(),
                            "barangay" to barangayDropdown.text.toString(),
                            "address" to address.text.toString(),
                            "verified" to true
                        )

                        // Add the user's email to the data to be saved
                        user.email?.let {
                            userData["email"] = it
                        }

                        if (profileImageUri != null) {
                            val storageRef = storage.reference.child("profile_images/${user.uid}.jpg")
                            storageRef.putFile(profileImageUri!!).addOnSuccessListener {
                                storageRef.downloadUrl.addOnSuccessListener { downloadUrl ->
                                    userData["profileImageUrl"] = downloadUrl.toString()
                                    saveUserData(user.uid, userData)
                                }
                            }.addOnFailureListener {
                                Toast.makeText(this, "Failed to upload image.", Toast.LENGTH_SHORT).show()
                                saveUserData(user.uid, userData)
                            }
                        } else {
                            saveUserData(user.uid, userData)
                        }
                    } else {
                        Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun saveUserData(uid: String, userData: Map<String, Any>) {
        db.collection("users").document(uid).set(userData)
            .addOnSuccessListener {
                Toast.makeText(this, "Profile updated successfully.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to update profile.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun fetchProvinces() {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces"
        findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.VISIBLE

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
                provinceDropdown.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, provinceNames)
                )
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            },
            { error ->
                Toast.makeText(this, "Failed to fetch provinces: ${error.message}", Toast.LENGTH_SHORT).show()
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            }
        )
        queue.add(jsonArrayRequest)
    }

    private fun fetchCities(provinceCode: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces/$provinceCode/cities-municipalities"
        findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.VISIBLE

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
                cityDropdown.isEnabled = true
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            },
            { error ->
                Toast.makeText(this, "Failed to fetch cities: ${error.message}", Toast.LENGTH_SHORT).show()
                cityDropdown.isEnabled = false
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            }
        )
        queue.add(jsonArrayRequest)
    }

    private fun fetchBarangays(provinceCode: String, cityCode: String) {
        val queue = Volley.newRequestQueue(this)
        val url = "https://psgc.gitlab.io/api/provinces/$provinceCode/barangays"
        findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.VISIBLE

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
                barangayDropdown.isEnabled = true
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            },
            { error ->
                Toast.makeText(this, "Failed to fetch barangays: ${error.message}", Toast.LENGTH_SHORT).show()
                barangayDropdown.isEnabled = false
                findViewById<ProgressBar>(R.id.loadingProgressBar).visibility = View.GONE
            }
        )
        queue.add(jsonArrayRequest)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            profileImageUri = data.data
            findViewById<ShapeableImageView>(R.id.profile_image).setImageURI(profileImageUri)
        }
    }
}
