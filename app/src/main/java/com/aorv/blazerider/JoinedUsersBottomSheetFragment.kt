package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.auth.FirebaseAuth
import java.io.Serializable

class JoinedUsersBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var joinedUsersAdapter: JoinedUsersAdapter
    private var joinedUsers: List<JoinedUser> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            joinedUsers = it.getSerializable(ARG_JOINED_USERS) as? List<JoinedUser> ?: emptyList()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_joined_users, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val joinedUsersList = view.findViewById<RecyclerView>(R.id.joined_users_list)
        joinedUsersList.layoutManager = LinearLayoutManager(context)

        val users = joinedUsers.ifEmpty {
            emptyList()
        }

        joinedUsersAdapter = JoinedUsersAdapter(users) { user ->
            val intent = Intent(context, ChatConversationActivity::class.java).apply {
                // Split name if possible for better contact data, or just use as firstName
                val nameParts = user.name.split(" ", limit = 2)
                val firstName = nameParts.getOrNull(0) ?: user.name
                val lastName = nameParts.getOrNull(1) ?: ""
                
                val contact = Contact(
                    id = user.userId,
                    firstName = firstName,
                    lastName = lastName,
                    profileImageUrl = user.profilePictureUrl
                )
                putExtra("CONTACT", contact)
                // We don't pass chatId here so ChatConversationActivity 
                // generates the stable ID based on current user and contact ID.
            }
            startActivity(intent)
            dismiss() // Close the bottom sheet after clicking
        }
        joinedUsersList.adapter = joinedUsersAdapter

        val bottomSheet = view.findViewById<View>(R.id.bottom_sheet_content)
            ?: return

        val behavior = BottomSheetBehavior.from(bottomSheet)
        if (users.isNotEmpty()) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        } else {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        behavior.peekHeight = (100 * resources.displayMetrics.density).toInt()
        behavior.isHideable = false
    }

    companion object {
        private const val ARG_JOINED_USERS = "joined_users"

        fun newInstance(joinedUsers: List<JoinedUser>): JoinedUsersBottomSheetFragment {
            val fragment = JoinedUsersBottomSheetFragment()
            val args = Bundle()
            args.putSerializable(ARG_JOINED_USERS, joinedUsers as Serializable)
            fragment.arguments = args
            return fragment
        }
    }
}