package com.aorv.blazerider

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObject

class UsersFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var userAdapter: UserAdapter
    private val userRequestList = mutableListOf<UserRequest>()

    // UI Elements
    private lateinit var btnPending: MaterialButton
    private lateinit var btnAccepted: MaterialButton
    private lateinit var btnDeleted: MaterialButton
    private lateinit var pendingBadge: TextView
    private lateinit var acceptedBadge: TextView
    private lateinit var deletedBadge: TextView
    private lateinit var searchBar: EditText
    private lateinit var tvTitle: TextView
    private lateinit var noRequestsText: TextView

    // State management: 0 = Pending, 1 = Accepted, 2 = Deleted
    private var currentTab = 0
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_users, container, false)

        // Initialize views
        recyclerView = view.findViewById(R.id.recyclerViewUserRequests)
        btnPending = view.findViewById(R.id.btnPending)
        btnAccepted = view.findViewById(R.id.btnAccepted)
        btnDeleted = view.findViewById(R.id.btnDeleted)
        pendingBadge = view.findViewById(R.id.pendingBadge)
        acceptedBadge = view.findViewById(R.id.acceptedBadge)
        deletedBadge = view.findViewById(R.id.deletedBadge)
        searchBar = view.findViewById(R.id.searchBar)
        tvTitle = view.findViewById(R.id.tvTitle)
        noRequestsText = view.findViewById(R.id.noRequestsText)

        arguments?.getString("initial_tab")?.let {
            if (it == "accepted") currentTab = 1
        }

        userAdapter = UserAdapter(
            userRequestList,
            requireContext(),
            currentTab,
            onConfirmClick = { user -> confirmUser(user) },
            onDeactivateClick = { user -> showDeactivationDialog(user) },
            onReactivateClick = { user -> reactivateUser(user) }
        )
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = userAdapter

        updateUIState()
        loadUsers()
        setupButtonListeners()
        setupSearchListener()

        return view
    }

    private fun loadUsers() {
        db.collection("users")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (context != null) Toast.makeText(context, "Error loading users: ${error.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }

                userRequestList.clear()
                var pendingCount = 0
                var acceptedCount = 0
                var deletedCount = 0

                snapshot?.documents?.forEach { doc ->
                    val user = doc.toObject<UserRequest>()?.apply { userId = doc.id }

                    if (user != null) {
                        val isAdmin = when (val adminValue = doc.get("admin")) {
                            is Boolean -> adminValue
                            is String -> adminValue.toBooleanStrictOrNull() ?: false
                            else -> false
                        }

                        // FIX: Access property directly for boolean named without "is"
                        val isDeactivated = user.deactivated

                        if (!isAdmin) {
                            userRequestList.add(user)
                            if (isDeactivated) {
                                deletedCount++
                            } else if (!user.isVerified) {
                                pendingCount++
                            } else {
                                acceptedCount++
                            }
                        }
                    }
                }

                updateBadges(pendingCount, acceptedCount, deletedCount)
                filterUsers(searchBar.text.toString())
            }
    }

    private fun confirmUser(user: UserRequest) {
        db.collection("users").document(user.userId ?: return)
            .update(
                mapOf(
                    "verified" to true,
                    "verifiedRecent" to true,
                    "deactivated" to false
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "User confirmed", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showDeactivationDialog(user: UserRequest) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "Enter reason for removal"

        val container = android.widget.FrameLayout(requireContext())
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(50, 20, 50, 20)
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(context)
            .setTitle("Remove User")
            .setMessage("Are you sure you want to deactivate ${user.firstName}? This will remove their posts, rides, and chats.")
            .setView(container)
            .setPositiveButton("Remove") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isNotEmpty()) {
                    deactivateUser(user, reason)
                } else {
                    Toast.makeText(context, "Reason is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UPDATED LOGIC TO DELETE RELATED DATA ---
    private fun deactivateUser(user: UserRequest, reason: String) {
        val userId = user.userId ?: return

        // 1. Mark user as deactivated first
        db.collection("users").document(userId)
            .update(
                mapOf(
                    "deactivated" to true,
                    "deactivationReason" to reason
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "User deactivated. Cleaning up data...", Toast.LENGTH_SHORT).show()

                // 2. Delete User's Posts
                deleteUserCollectionData("posts", "userId", userId)

                // 3. Delete User's Rides (if they created any)
                deleteUserCollectionData("rides", "driverId", userId) // Assuming driverId links to user

                // 4. Delete Chats (Optional: Deleting messages sent by them)
                // Note: Deleting individual messages inside chats is complex and costly.
                // Often better to keep messages but show "Deactivated User" name.
                // If you must delete 1-on-1 chats entirely:
                deleteUserChats(userId)
            }
            .addOnFailureListener {
                Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // Helper function to delete documents where a specific field matches the userId
    private fun deleteUserCollectionData(collectionName: String, fieldName: String, userId: String) {
        db.collection(collectionName)
            .whereEqualTo(fieldName, userId)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = db.batch()
                for (document in snapshot.documents) {
                    batch.delete(document.reference)
                }
                batch.commit().addOnSuccessListener {
                    // Log or Toast success for this collection
                }
            }
    }

    private fun deleteUserChats(userId: String) {
        // Logic depends on your Chat structure.
        // Example: If participants is an array [uid1, uid2]
        db.collection("chats")
            .whereArrayContains("participants", userId)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = db.batch()
                for (document in snapshot.documents) {
                    batch.delete(document.reference)
                }
                batch.commit()
            }
    }

    private fun reactivateUser(user: UserRequest) {
        db.collection("users").document(user.userId ?: return)
            .update(
                mapOf(
                    "deactivated" to false,
                    "deactivationReason" to FieldValue.delete()
                )
            )
            .addOnSuccessListener {
                Toast.makeText(context, "User reactivated", Toast.LENGTH_SHORT).show()
            }
    }

    private fun filterUsers(query: String) {
        val filteredList = userRequestList.filter { user ->
            // FIX: Using correct property access
            val isDeactivated = user.deactivated

            val matchesStatus = when (currentTab) {
                0 -> !user.isVerified && !isDeactivated // Pending
                1 -> user.isVerified && !isDeactivated  // Accepted
                2 -> isDeactivated                      // Deleted
                else -> false
            }

            val fullName = "${user.firstName} ${user.lastName}".lowercase()
            val matchesSearch = query.isEmpty() || fullName.contains(query.lowercase())
            matchesStatus && matchesSearch
        }

        userAdapter.updateList(filteredList, currentTab)

        recyclerView.visibility = if (filteredList.isEmpty()) View.GONE else View.VISIBLE
        noRequestsText.visibility = if (filteredList.isEmpty()) View.VISIBLE else View.GONE
        noRequestsText.text = if (filteredList.isEmpty()) "No users found" else ""
    }

    private fun updateBadges(pendingCount: Int, acceptedCount: Int, deletedCount: Int) {
        pendingBadge.apply {
            visibility = if (pendingCount > 0) View.VISIBLE else View.GONE
            text = pendingCount.toString()
        }
        acceptedBadge.apply {
            visibility = if (acceptedCount > 0) View.VISIBLE else View.GONE
            text = acceptedCount.toString()
        }
        deletedBadge.apply {
            visibility = if (deletedCount > 0) View.VISIBLE else View.GONE
            text = deletedCount.toString()
        }
    }

    private fun setupButtonListeners() {
        btnPending.setOnClickListener {
            currentTab = 0
            updateUIState()
            filterUsers(searchBar.text.toString())
        }
        btnAccepted.setOnClickListener {
            currentTab = 1
            updateUIState()
            filterUsers(searchBar.text.toString())
        }
        btnDeleted.setOnClickListener {
            currentTab = 2
            updateUIState()
            filterUsers(searchBar.text.toString())
        }
    }

    private fun updateUIState() {
        btnPending.isSelected = currentTab == 0
        btnAccepted.isSelected = currentTab == 1
        btnDeleted.isSelected = currentTab == 2

        tvTitle.text = when (currentTab) {
            0 -> "Pending Users"
            1 -> "Confirmed Users"
            2 -> "Deleted Users"
            else -> "Users"
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
}