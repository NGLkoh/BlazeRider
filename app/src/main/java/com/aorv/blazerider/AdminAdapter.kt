package com.aorv.blazerider

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AdminAdapter(private var adminList: List<Admin>) :
    RecyclerView.Adapter<AdminAdapter.AdminViewHolder>() {

    class AdminViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.admin_name)
        val emailText: TextView = view.findViewById(R.id.admin_email)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdminViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin, parent, false)
        return AdminViewHolder(view)
    }

    override fun onBindViewHolder(holder: AdminViewHolder, position: Int) {
        val admin = adminList[position]
        holder.nameText.text = "${admin.firstName} ${admin.lastName}"
        holder.emailText.text = admin.email
    }

    override fun getItemCount() = adminList.size

    fun updateList(newList: List<Admin>) {
        adminList = newList
        notifyDataSetChanged()
    }
}