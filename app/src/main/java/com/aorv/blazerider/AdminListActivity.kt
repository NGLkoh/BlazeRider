package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class AdminListActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var adapter: AdminAdapter
    private val adminList = mutableListOf<Admin>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_list)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        setupToolbar()
        setupRecyclerView()
        setupFab()
        fetchAdmins()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.admin_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AdminAdapter(adminList)
        recyclerView.adapter = adapter
    }

    private fun setupFab() {
        val fab = findViewById<FloatingActionButton>(R.id.fab_add_admin)
        fab.visibility = android.view.View.VISIBLE
        fab.setOnClickListener {
            val intent = Intent(this, AddAdminActivity::class.java)
            startActivity(intent)
        }
    }

    private fun fetchAdmins() {
        db.collection("users")
            .whereEqualTo("admin", true)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener
                val list = snapshots.toObjects(Admin::class.java)
                adapter.updateList(list)
            }
    }
}
