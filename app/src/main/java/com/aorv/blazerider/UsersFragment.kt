package com.aorv.blazerider

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject

class UsersFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var userAdapter: UserAdapter
    private val userRequestList = mutableListOf<UserRequest>()
    private lateinit var btnPending: MaterialButton
    private lateinit var btnAccepted: MaterialButton
    private lateinit var pendingBadge: TextView
    private lateinit var acceptedBadge: TextView
    private lateinit var searchBar: EditText
    private lateinit var tvTitle: TextView
    private lateinit var noRequestsText: TextView
    private var showPending = true
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_users, container, false)

        // Initialize views
        recyclerView = view.findViewById(R.id.recyclerViewUserRequests)
        btnPending = view.findViewById(R.id.btnPending)
        btnAccepted = view.findViewById(R.id.btnAccepted)
        pendingBadge = view.findViewById(R.id.pendingBadge)
        acceptedBadge = view.findViewById(R.id.acceptedBadge)
        searchBar = view.findViewById(R.id.searchBar)
        tvTitle = view.findViewById(R.id.tvTitle)
        noRequestsText = view.findViewById(R.id.noRequestsText)

        // Setup RecyclerView
        userAdapter = UserAdapter(userRequestList, requireContext(), showPending) { user ->
            confirmUser(user)
        }
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = userAdapter

        // Load users and setup listeners
        loadUsers()
        setupButtonListeners()
        setupSearchListener()

        return view
    }

    private fun loadUsers() {
        db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(context, "Error loading users: ${error.message}", Toast.LENGTH_SHORT).show()
                    recyclerView.visibility = View.GONE
                    noRequestsText.visibility = View.VISIBLE
                    return@addSnapshotListener
                }

                userRequestList.clear()
                var pendingCount = 0
                var acceptedCount = 0

                snapshot?.documents?.forEach { doc ->
                    val user = doc.toObject<UserRequest>()?.apply { userId = doc.id }
                    if (user != null) {
                        // Handle inconsistent admin field (string or boolean)
                        val isAdmin = when (val adminValue = doc.get("admin")) {
                            is Boolean -> adminValue
                            is String -> adminValue.toBooleanStrictOrNull() ?: false
                            else -> false
                        }

                        if (!isAdmin) { // Exclude admin users
                            userRequestList.add(user)
                            if (!user.isVerified) pendingCount++
                            else acceptedCount++
                        }
                    }
                }

                updateBadges(pendingCount, acceptedCount)
                filterUsers(searchBar.text.toString())
            }
    }
    private fun confirmUser(user: UserRequest) {
        db.collection("users").document(user.userId ?: return)
            .update(
                mapOf(
                    "verified" to true,
                    "verifiedRecent" to true
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "User confirmed", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(context, "Error confirming user", Toast.LENGTH_SHORT).show()
            }
    }

    private fun filterUsers(query: String) {
        val filteredList = userRequestList.filter { user ->
            val matchesStatus = if (showPending) !user.isVerified else user.isVerified
            val fullName = "${user.firstName} ${user.lastName}".lowercase()
            val matchesSearch = query.isEmpty() || fullName.contains(query.lowercase())
            matchesStatus && matchesSearch
        }

        userAdapter.updateList(filteredList, showPending)
        recyclerView.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
        noRequestsText.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateBadges(pendingCount: Int, acceptedCount: Int) {
        pendingBadge.apply {
            visibility = if (pendingCount > 0) View.VISIBLE else View.GONE
            text = pendingCount.toString()
        }
        acceptedBadge.apply {
            visibility = if (acceptedCount > 0) View.VISIBLE else View.GONE
            text = acceptedCount.toString()
        }
    }

    private fun setupButtonListeners() {
        btnPending.setOnClickListener {
            showPending = true
            tvTitle.text = "User Requests"
            updateButtonStates()
            filterUsers(searchBar.text.toString())
        }
        btnAccepted.setOnClickListener {
            showPending = false
            tvTitle.text = "Confirmed Users"
            updateButtonStates()
            filterUsers(searchBar.text.toString())
        }
    }

    private fun setupSearchListener() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                filterUsers(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateButtonStates() {
        btnPending.isSelected = showPending
        btnAccepted.isSelected = !showPending
    }
}
