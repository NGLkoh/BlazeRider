package com.aorv.blazerider

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.aorv.blazerider.databinding.ItemContactGroupBinding

class ContactGroupAdapter(private val onContactClick: (Contact) -> Unit) : RecyclerView.Adapter<ContactGroupAdapter.ViewHolder>() {
    private val contacts = mutableListOf<Contact>()
    private val filteredContacts = mutableListOf<Contact>()
    private var noResultsCallback: ((Boolean) -> Unit)? = null

    class ViewHolder(
        private val binding: ItemContactGroupBinding,
        private val onClick: (Contact) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: Contact, isSelected: Boolean) {
            binding.contactName.text = "${contact.firstName} ${contact.lastName}"
            contact.profileImageUrl?.let { url ->
                Glide.with(binding.contactImage.context)
                    .load(url)
                    .placeholder(R.drawable.ic_anonymous)
                    .error(R.drawable.ic_anonymous)
                    .into(binding.contactImage)
            } ?: binding.contactImage.setImageResource(R.drawable.ic_anonymous)
            // Toggle checkmark visibility
            binding.checkBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { onClick(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onContactClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = filteredContacts[position]
        val isSelected = (holder.itemView.context as? NewGroupCommunityActivity)?.selectedContacts?.contains(contact) ?: false
        holder.bind(contact, isSelected)
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