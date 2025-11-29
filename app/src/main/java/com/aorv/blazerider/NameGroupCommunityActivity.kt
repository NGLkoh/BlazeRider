package com.aorv.blazerider

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aorv.blazerider.databinding.ActivityNameGroupCommunityBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.UUID

class NameGroupCommunityActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNameGroupCommunityBinding
    private val selectedContacts by lazy {
        intent.getSerializableExtra("SELECTED_CONTACTS") as? ArrayList<Contact> ?: arrayListOf()
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                binding.cameraIcon.setImageURI(uri)
                binding.cameraIcon.tag = uri // Store URI in tag
            } ?: Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameGroupCommunityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Validate selected contacts
        if (selectedContacts.isEmpty()) {
            Toast.makeText(this, "No contacts selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Handle back button
        binding.backButton.setOnClickListener {
            finish()
        }

        // Enable/disable check button based on group name input
        binding.groupNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.checkButton.isEnabled = !s.isNullOrBlank()
            }
        })

        // Handle camera icon click to launch gallery
        binding.cameraIcon.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncher.launch(intent)
        }

        // Handle check button click
        binding.checkButton.setOnClickListener {
            val groupName = binding.groupNameInput.text.toString().trim()
            if (groupName.isNotBlank()) {
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    Toast.makeText(this, "User not logged in", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Show progress dialog
                val progressDialog = ProgressDialog(this).apply {
                    setMessage("Creating group chat please wait...")
                    setCancelable(false)
                    show()
                }

                // Prepare group data
                val chatId = UUID.randomUUID().toString()
                val members = mutableMapOf<String, Any>()
                members[currentUser.uid] = mapOf("joinedAt" to Timestamp.now(), "role" to "member")
                selectedContacts.forEach { contact ->
                    members[contact.id] = mapOf("joinedAt" to Timestamp.now(), "role" to "member")
                }

                val groupData = hashMapOf(
                    "type" to "group",
                    "name" to groupName,
                    "members" to members,
                    "createdAt" to Timestamp.now(),
                    "lastMessage" to mapOf(
                        "content" to "Say \"Hi\" to $groupName",
                        "senderId" to null,
                        "timestamp" to Timestamp.now()
                    ),
                    "groupImage" to null as Any? // Explicitly set groupImage to null initially
                )

                // Handle group image if selected
                val imageUri = binding.cameraIcon.tag as? Uri
                if (imageUri != null) {
                    val storageRef = FirebaseStorage.getInstance().reference.child("group_images/$chatId.jpg")
                    storageRef.putFile(imageUri)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) throw task.exception!!
                            storageRef.downloadUrl
                        }
                        .addOnSuccessListener { downloadUrl ->
                            groupData["groupImage"] = downloadUrl.toString()
                            saveGroupToFirestore(chatId, groupData, progressDialog)
                        }
                        .addOnFailureListener { exception ->
                            progressDialog.dismiss()
                            Toast.makeText(this, "Failed to upload image: ${exception.message}", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    saveGroupToFirestore(chatId, groupData, progressDialog)
                }
            }
        }
    }

    private fun saveGroupToFirestore(chatId: String, groupData: HashMap<String, Any?>, progressDialog: ProgressDialog) {
        val db = FirebaseFirestore.getInstance()
        db.collection("chats").document(chatId)
            .set(groupData)
            .addOnSuccessListener {
                // Update userChats for each member
                groupData["members"]?.let { members ->
                    (members as Map<String, Map<String, Any>>).keys.forEach { userId ->
                        db.collection("userChats").document(userId)
                            .collection("chats").document(chatId)
                            .set(mapOf(
                                "lastMessage" to mapOf(
                                    "content" to "Say \"Hi\" to ${groupData["name"]}",
                                    "senderId" to null,
                                    "timestamp" to Timestamp.now()
                                ),
                                "unreadCount" to 0,
                                "name" to groupData["name"],
                                "type" to "group",
                                "profileImage" to groupData["groupImage"]
                            ))
                    }
                }
                progressDialog.dismiss()
                Toast.makeText(this, "Group created successfully", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, MessagesActivity::class.java)
                intent.putExtra("CHAT_ID", chatId)
                intent.putExtra("CHAT_NAME", groupData["name"] as String)
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { exception ->
                progressDialog.dismiss()
                Toast.makeText(this, "Failed to create group: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }
}