package com.aorv.blazerider

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.math.max

class PostActivity : AppCompatActivity() {

    private val selectedImageUris = mutableListOf<Uri>()
    private lateinit var postButton: TextView
    private lateinit var imageViewPager: ViewPager2
    private lateinit var imageCounter: TextView
    private lateinit var removeImageButton: ImageButton
    private lateinit var profileName: TextView
    private lateinit var profilePic: ShapeableImageView
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var progressDialog: AlertDialog? = null
    private lateinit var progressText: TextView
    private var isAdmin: Boolean = false // Default to false

    // Variable to hold the scheduled time
    private var scheduledTimestamp: Long = 0L

    // Photo picker for Android 13+
    private val pickImagesModern = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris.clear()
            selectedImageUris.addAll(uris)
            updateImagePreviews()
            Toast.makeText(this, "${uris.size} image(s) selected", Toast.LENGTH_SHORT).show()
        }
    }

    // Fallback for pre-Android 13
    private val pickImagesLegacy = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedImageUris.clear()
            selectedImageUris.addAll(uris)
            updateImagePreviews()
            Toast.makeText(this, "${uris.size} image(s) selected", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission request for pre-Android 13
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pickImagesLegacy.launch("image/*")
        } else {
            Toast.makeText(this, "Permission denied to access images", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post)

        // Get admin status from intent
        isAdmin = intent.getBooleanExtra("admin", false)

        // Get the scheduled date passed from EventsFragment
        scheduledTimestamp = intent.getLongExtra("SCHEDULED_DATE", 0L)

        // Light status bar icons
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.statusBarColor = getColor(android.R.color.white)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val appBarLayout = findViewById<View>(R.id.appbar_layout)
        val mainContent = findViewById<LinearLayout>(R.id.main_content)
        val postComposerToolbar = findViewById<BottomNavigationView>(R.id.post_composer_toolbar)
        profileName = findViewById(R.id.profile_name)
        profilePic = findViewById(R.id.profile_pic)
        imageViewPager = findViewById(R.id.image_view_pager)
        imageCounter = findViewById(R.id.image_counter)
        removeImageButton = findViewById(R.id.remove_image_button)
        postButton = findViewById(R.id.post_button)

        // Find the schedule info text view (make sure you added this ID to your XML)
        val scheduleInfoText = findViewById<TextView>(R.id.schedule_info_text)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Adjust AppBarLayout for status bar
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = statusBarHeight
            view.layoutParams = layoutParams
            insets
        }

        // Adjust main content for navigation bar (not keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { view, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navBarHeight
            )
            insets
        }

        // Adjust post composer toolbar for keyboard and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(postComposerToolbar) { view, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                max(navBarHeight, keyboardHeight)
            )
            insets
        }

        // Set up ViewPager2
        imageViewPager.adapter = ImagePagerAdapter()

        // Update image counter and remove button when swiping
        imageViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateImageCounter()
                updateRemoveButton()
            }
        })

        // Load user profile
        loadUserProfile()

        // Post button setup
        postButton.isEnabled = false
        postButton.alpha = 0.5f

        // --- NEW LOGIC: Update UI if Scheduled ---
        if (scheduledTimestamp > 0) {
            val date = java.util.Date(scheduledTimestamp)
            val format = java.text.SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
            val formattedDate = format.format(date)

            if (scheduleInfoText != null) {
                scheduleInfoText.text = "Scheduled for: $formattedDate"
                scheduleInfoText.visibility = View.VISIBLE
            }
            postButton.text = "Schedule"
        } else {
            if (scheduleInfoText != null) {
                scheduleInfoText.visibility = View.GONE
            }
            postButton.text = "Post"
        }
        // -----------------------------------------

        val postContent = findViewById<EditText>(R.id.post_content)
        postContent.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val enabled = !s.isNullOrBlank() && s.toString().trim().isNotEmpty()
                postButton.isEnabled = enabled
                postButton.alpha = if (enabled) 1f else 0.5f
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        postButton.setOnClickListener {
            val content = postContent.text.toString().trim()

            if (content.isNotEmpty()) {
                // Show Confirmation Dialog
                val actionWord = if (scheduledTimestamp > 0) "schedule" else "post"
                AlertDialog.Builder(this)
                    .setTitle("Confirm Post")
                    .setMessage("Are you sure you want to $actionWord it now?")
                    .setPositiveButton("Yes") { _, _ ->
                        // Proceed with posting if user clicks Yes
                        showProgressDialog()
                        savePostToFirebase(content)
                    }
                    .setNegativeButton("No") { dialog, _ ->
                        // Just close the dialog if user clicks No
                        dialog.dismiss()
                    }
                    .show()
            } else {
                Toast.makeText(this, "Post content cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Add picture action
        postComposerToolbar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_add_picture -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pickImagesModern.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        requestPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    true
                }
                else -> false
            }
        }

        // Remove image button
        removeImageButton.setOnClickListener {
            val currentPosition = imageViewPager.currentItem
            if (selectedImageUris.isNotEmpty() && currentPosition < selectedImageUris.size) {
                selectedImageUris.removeAt(currentPosition)
                updateImagePreviews()
                Toast.makeText(this, "Image removed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showProgressDialog() {
        val builder = AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(android.R.layout.simple_list_item_1, null)
        progressText = dialogView.findViewById(android.R.id.text1)
        progressText.text = "Processing, please wait... 0%"

        builder.setView(dialogView)
            .setTitle(if (scheduledTimestamp > 0) "Scheduling" else "Posting")
            .setCancelable(false)

        progressDialog = builder.create()
        progressDialog?.show()
    }

    private fun updateProgress(progress: Int) {
        runOnUiThread {
            progressText.text = "Processing, please wait... $progress%"
        }
    }

    private fun dismissProgressDialog() {
        runOnUiThread {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    private fun loadUserProfile() {
        val user = auth.currentUser
        if (user != null) {
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val firstName = document.getString("firstName") ?: ""
                        val lastName = document.getString("lastName") ?: ""
                        val fullName = "$firstName $lastName".trim()
                        profileName.text = if (fullName.isNotEmpty()) fullName else "Anonymous"
                        val profileUrl = document.getString("profileImageUrl")
                        if (!profileUrl.isNullOrEmpty()) {
                            Glide.with(this)
                                .load(profileUrl)
                                .placeholder(R.drawable.ic_anonymous)
                                .error(R.drawable.ic_anonymous)
                                .into(profilePic)
                        } else {
                            profilePic.setImageResource(R.drawable.ic_anonymous)
                        }
                    } else {
                        profileName.text = "Anonymous"
                        profilePic.setImageResource(R.drawable.ic_anonymous)
                    }
                }
                .addOnFailureListener {
                    profileName.text = "Anonymous"
                    profilePic.setImageResource(R.drawable.ic_anonymous)
                }
        } else {
            profileName.text = "Anonymous"
            profilePic.setImageResource(R.drawable.ic_anonymous)
        }
    }

    private fun updateImagePreviews() {
        imageViewPager.adapter?.notifyDataSetChanged()
        updateImageCounter()
        updateRemoveButton()
    }

    private fun updateImageCounter() {
        if (selectedImageUris.size <= 1) {
            imageCounter.visibility = View.GONE
        } else {
            val currentPosition = imageViewPager.currentItem + 1
            val totalImages = selectedImageUris.size
            imageCounter.text = "$currentPosition/$totalImages"
            imageCounter.visibility = View.VISIBLE
        }
    }

    private fun updateRemoveButton() {
        removeImageButton.visibility = if (selectedImageUris.isEmpty()) View.GONE else View.VISIBLE
    }

    private suspend fun uploadImages(postId: String): List<String> {
        val downloadUrls = mutableListOf<String>()
        val totalImages = selectedImageUris.size
        var imagesUploaded = 0

        for (uri in selectedImageUris) {
            val imageId = UUID.randomUUID().toString()
            val storageRef = storage.reference.child("posts/$postId/images/$imageId")
            try {
                val uploadTask = storageRef.putFile(uri)
                uploadTask.addOnProgressListener { taskSnapshot ->
                    val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toInt()
                    val overallProgress = ((imagesUploaded * 100 + progress) / (totalImages + 1)).toInt()
                    updateProgress(overallProgress)
                }.await()
                imagesUploaded++
                val downloadUrl = storageRef.downloadUrl.await().toString()
                downloadUrls.add(downloadUrl)
            } catch (e: Exception) {
                Log.e("PostActivity", "Failed to upload image: ${e.message}")
                continue
            }
        }
        return downloadUrls
    }

    private fun savePostToFirebase(content: String) {
        val user = auth.currentUser
        if (user == null) {
            runOnUiThread {
                dismissProgressDialog()
                Toast.makeText(this, "Please sign in to post", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Create a post document reference to get the postId
        val postRef = db.collection("posts").document()
        val postId = postRef.id

        GlobalScope.launch(Dispatchers.IO) {
            val imageUrls = uploadImages(postId)

            // Check if it's scheduled (timestamp > 0)
            val isScheduled = scheduledTimestamp > 0

            val creationDate: Any = if (isScheduled) {
                java.util.Date(scheduledTimestamp)
            } else {
                FieldValue.serverTimestamp()
            }

            val post = hashMapOf(
                "userId" to user.uid,
                "content" to content,
                "createdAt" to creationDate,
                "imageUris" to imageUrls,
                "reactionCount" to mapOf(
                    "angry" to 0, "haha" to 0, "like" to 0,
                    "love" to 0, "sad" to 0, "wow" to 0
                ),
                "admin" to isAdmin,
                "isScheduled" to isScheduled, // <--- SAVE THIS FLAG
                "commentsCount" to 0
            )

            try {
                postRef.set(post).await()
                updateProgress(100)
                runOnUiThread {
                    dismissProgressDialog()
                    val successMsg = if (scheduledTimestamp > 0) "Event scheduled successfully" else "Post submitted successfully"
                    Toast.makeText(this@PostActivity, successMsg, Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dismissProgressDialog()
                    Toast.makeText(this@PostActivity, "Failed to submit post: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private inner class ImagePagerAdapter : RecyclerView.Adapter<ImagePagerAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val imageView: ImageView = itemView.findViewById(android.R.id.icon)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val imageView = ImageView(parent.context).apply {
                id = android.R.id.icon
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            return ViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.imageView.setImageURI(selectedImageUris[position])
        }

        override fun getItemCount(): Int = selectedImageUris.size
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}