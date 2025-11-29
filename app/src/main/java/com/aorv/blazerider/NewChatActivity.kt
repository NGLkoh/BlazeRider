package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.firestore.FirebaseFirestore
import com.aorv.blazerider.databinding.ActivityNewChatBinding
import com.aorv.blazerider.databinding.ItemContactBinding
import com.google.firebase.Timestamp
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String,
    val profileImageUrl: String?,
    val email: String? = null,
    val lastActive: String? = null
) : Serializable

class ContactAdapter(private val onContactClick: (Contact) -> Unit) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {
    private val contacts = mutableListOf<Contact>()
    private val filteredContacts = mutableListOf<Contact>()
    private var noResultsCallback: ((Boolean) -> Unit)? = null

    class ViewHolder(private val binding: ItemContactBinding, private val onClick: (Contact) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: Contact) {
            binding.contactName.text = "${contact.firstName} ${contact.lastName}"
            contact.profileImageUrl?.let { url ->
                Glide.with(binding.contactImage.context)
                    .load(url)
                    .placeholder(R.drawable.ic_anonymous)
                    .error(R.drawable.ic_anonymous)
                    .into(binding.contactImage)
            } ?: binding.contactImage.setImageResource(R.drawable.ic_anonymous)
            binding.root.setOnClickListener { onClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onContactClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(filteredContacts[position])
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
        filteredContacts.clear()
        if (query.isEmpty()) {
            filteredContacts.addAll(contacts)
        } else {
            val lowercaseQuery = query.lowercase()
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

        // Set up RecyclerView
        adapter = ContactAdapter { contact ->
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("CONTACT", contact)
            startActivity(intent)
        }
        binding.searchRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchRecyclerView.adapter = adapter

        // Set up no results callback
        adapter.setNoResultsCallback { isEmpty ->
            binding.noResultsText.visibility = if (isEmpty && isSearchActive) View.VISIBLE else View.GONE
        }

        // Fetch contacts from Firestore
        fetchContacts()

        // Handle back button click
        binding.backButton.setOnClickListener {
            if (isSearchActive) {
                toggleSearch(false)
            } else {
                finish()
            }
        }

        // Handle group chat button click
        binding.startGroupChat.setOnClickListener {
            startActivity(Intent(this, NewGroupCommunityActivity::class.java))
        }

        // Handle search button click to toggle search input
        binding.searchButton.setOnClickListener {
            if (isSearchActive) {
                binding.searchInput.text.clear()
                toggleSearch(false)
            } else {
                toggleSearch(true)
            }
        }

        // Add TextWatcher for real-time search filtering
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
            adapter.filter(binding.searchInput.text.toString())
        } else {
            binding.searchInput.visibility = View.GONE
            binding.toolbarTitle.visibility = View.VISIBLE
            binding.searchInput.text.clear()
            adapter.filter("")
            binding.searchButton.setImageResource(R.drawable.ic_search)
            binding.noResultsText.visibility = View.GONE
        }
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
                    // Handle lastActive as Timestamp
                    val lastActive = when (val value = document.get("lastActive")) {
                        is Timestamp -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(value.toDate())
                        else -> null
                    }
                    contacts.add(Contact(document.id, firstName, lastName, profileImageUrl, email, lastActive))
                }
                adapter.updateContacts(contacts)
            }
            .addOnFailureListener { exception ->
                android.widget.Toast.makeText(this, "Failed to load contacts: ${exception.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
}