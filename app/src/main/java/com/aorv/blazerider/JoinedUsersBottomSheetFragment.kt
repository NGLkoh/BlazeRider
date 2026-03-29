package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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

        joinedUsersAdapter = JoinedUsersAdapter(joinedUsers) { user ->
            val intent = Intent(context, ChatConversationActivity::class.java).apply {
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
            }
            startActivity(intent)
            dismiss()
        }
        joinedUsersList.adapter = joinedUsersAdapter
        
        // Disable internal scrolling for the list so the BottomSheet handles it,
        // and specifically prevent any scrolling feel if only one item is present.
        joinedUsersList.isNestedScrollingEnabled = joinedUsers.size > 3 
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            
            // Ensure the sheet fits its content to avoid empty white space
            behavior.isFitToContents = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            
            // Disable dragging/scrolling the sheet if there's only one user
            if (joinedUsers.size <= 1) {
                behavior.isDraggable = false
            } else {
                behavior.isDraggable = true
                behavior.skipCollapsed = true // Avoid intermediate collapsed state
            }
        }
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
