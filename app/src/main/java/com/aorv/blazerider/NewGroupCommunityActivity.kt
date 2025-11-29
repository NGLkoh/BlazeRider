package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.aorv.blazerider.databinding.ActivityNewGroupCommunityBinding
import java.text.SimpleDateFormat
import java.util.Locale

class NewGroupCommunityActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNewGroupCommunityBinding
    private lateinit var adapter: ContactGroupAdapter
    private val db = FirebaseFirestore.getInstance()
    val selectedContacts = mutableListOf<Contact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewGroupCommunityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up RecyclerView
        adapter = ContactGroupAdapter { contact ->
            if (selectedContacts.contains(contact)) {
                selectedContacts.remove(contact)
            } else {
                selectedContacts.add(contact)
            }
            updateProceedButton()
            adapter.notifyDataSetChanged()
        }
        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.contactsRecyclerView.adapter = adapter

        // Set up no results callback for search
        adapter.setNoResultsCallback { isEmpty ->
            // Optionally show a "No results" message if needed
        }

        // Fetch contacts from Firestore
        fetchContacts()

        // Handle back button click
        binding.backButton.setOnClickListener {
            finish()
        }

        // Handle search input for filtering
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s.toString())
            }
        })

        // Handle proceed button click
        binding.proceedButton.setOnClickListener {
            if (selectedContacts.size >= 2) {
                val intent = Intent(this, NameGroupCommunityActivity::class.java)
                intent.putExtra("SELECTED_CONTACTS", ArrayList(selectedContacts))
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please select at least 2 contacts to create a group", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize proceed button state
        updateProceedButton()
    }

    private fun fetchContacts() {
        db.collection("users")
            .get()
            .addOnSuccessListener { result ->
                val contacts = mutableListOf<Contact>()
                for (document in result) {
                    val firstName = document.getString("firstName") ?: ""
                    val lastName = document.getString("lastName") ?: ""
                    val profileImageUrl = document.getString("profileImageUrl")
                    val email = document.getString("email")
                    val lastActive = when (val value = document.get("lastActive")) {
                        is Timestamp -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(value.toDate())
                        else -> null
                    }
                    contacts.add(Contact(document.id, firstName, lastName, profileImageUrl, email, lastActive))
                }
                adapter.updateContacts(contacts)
            }
            .addOnFailureListener { exception ->
                Toast.makeText(this, "Failed to load contacts: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateProceedButton() {
        val selectedCount = selectedContacts.size
        binding.proceedButton.isEnabled = selectedCount >= 2
        binding.proceedBadge.apply {
            visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
            text = selectedCount.toString()
        }
    }
}