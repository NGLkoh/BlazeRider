package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.aorv.blazerider.databinding.ActivityNewChatBinding
import com.aorv.blazerider.databinding.ItemContactBinding
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.Locale

class ContactAdapter(private val onContactClick: (Contact) -> Unit) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {
    private val contacts = mutableListOf<Contact>()
    private val filteredContacts = mutableListOf<Contact>()
    private var noResultsCallback: ((Boolean) -> Unit)? = null

    class ViewHolder(private val binding: ItemContactBinding, private val onClick: (Contact) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: Contact) {
            binding.contactName.text = "${contact.firstName} ${contact.lastName}".trim()

            Glide.with(binding.contactImage.context)
                .load(contact.profileImageUrl)
                .placeholder(R.drawable.ic_anonymous)
                .error(R.drawable.ic_anonymous)
                .circleCrop()
                .into(binding.contactImage)

            binding.root.setOnClickListener {
                onClick(contact)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onContactClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < filteredContacts.size) {
            holder.bind(filteredContacts[position])
        }
    }

    override fun getItemCount(): Int = filteredContacts.size

    fun updateContacts(newContacts: List<Contact>) {
        contacts.clear()
        contacts.addAll(newContacts.sortedBy { it.firstName.lowercase() })
        filteredContacts.clear()
        filteredContacts.addAll(contacts)
        noResultsCallback?.invoke(filteredContacts.isEmpty())
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        val lowercaseQuery = query.lowercase().trim()
        filteredContacts.clear()
        if (lowercaseQuery.isEmpty()) {
            filteredContacts.addAll(contacts)
        } else {
            filteredContacts.addAll(contacts.filter {
                it.firstName.lowercase().contains(lowercaseQuery) ||
                        it.lastName.lowercase().contains(lowercaseQuery) ||
                        "${it.firstName} ${it.lastName}".lowercase().contains(lowercaseQuery)
            })
        }
        noResultsCallback?.invoke(filteredContacts.isEmpty())
        notifyDataSetChanged()
    }

    fun setNoResultsCallback(callback: (Boolean) -> Unit) {
        noResultsCallback = callback
    }
}

class NewChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityNewChatBinding
    private lateinit var adapter: ContactAdapter
    private val db = FirebaseFirestore.getInstance()
    private var isSearchActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ContactAdapter { contact ->
            try {
                if (contact.id.isEmpty()) {
                    Toast.makeText(this, "Invalid User Data", Toast.LENGTH_SHORT).show()
                    return@ContactAdapter
                }

                // FRESH START LOGIC:
                // We do NOT look for an existing chatId here.
                // We simply pass the CONTACT object.
                val intent = Intent(this, ChatConversationActivity::class.java)
                intent.putExtra("CONTACT", contact)

                // Clear this activity from stack so they go directly to the chat
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Log.e("NAV_ERROR", "Failed to open chat: ${e.message}")
            }
        }

        binding.searchRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchRecyclerView.adapter = adapter

        adapter.setNoResultsCallback { isEmpty ->
            binding.noResultsText.visibility = if (isEmpty && isSearchActive) View.VISIBLE else View.GONE
        }

        fetchContacts()

        binding.backButton.setOnClickListener {
            if (isSearchActive) toggleSearch(false) else finish()
        }

        binding.startGroupChat.setOnClickListener {
            startActivity(Intent(this, NewGroupCommunityActivity::class.java))
        }

        binding.searchButton.setOnClickListener {
            toggleSearch(!isSearchActive)
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s.toString())
            }
        })
    }

    private fun toggleSearch(active: Boolean) {
        isSearchActive = active
        if (active) {
            binding.toolbarTitle.visibility = View.GONE
            binding.searchInput.visibility = View.VISIBLE
            binding.searchInput.requestFocus()
        } else {
            binding.searchInput.visibility = View.GONE
            binding.toolbarTitle.visibility = View.VISIBLE
            binding.searchInput.text.clear()
            adapter.filter("")
        }
    }

    private fun fetchContacts() {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

        db.collection("users")
            .get()
            .addOnSuccessListener { result ->
                val contactsList = result.mapNotNull { document ->
                    val id = document.id

                    // Skip the current user
                    if (id == currentUserId) return@mapNotNull null

                    try {
                        val fName = document.getString("firstName") ?: "Unknown"
                        val lName = document.getString("lastName") ?: ""
                        val img = document.getString("profileImageUrl")
                        val mail = document.getString("email")

                        val activeTime = when (val value = document.get("lastActive")) {
                            is Timestamp -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(value.toDate())
                            else -> null
                        }

                        Contact(id, fName, lName, img, mail, activeTime)
                    } catch (e: Exception) {
                        Log.e("FETCH_ERROR", "Error parsing user: ${document.id}")
                        null
                    }
                }
                adapter.updateContacts(contactsList)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}