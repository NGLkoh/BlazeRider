package com.aorv.blazerider

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class DashboardFragment : Fragment() {
    private lateinit var lineChart: LineChart
    private lateinit var segmentedControl: RadioGroup
    private lateinit var tvTotalUsers: TextView
    private val db = FirebaseFirestore.getInstance()
    private var allVerifiedUsers: List<DocumentSnapshot> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lineChart = view.findViewById(R.id.lineChart)
        segmentedControl = view.findViewById(R.id.segmentedControl)
        tvTotalUsers = view.findViewById(R.id.tv_total_users)

        fetchAllVerifiedUsers()

        segmentedControl.setOnCheckedChangeListener { _, checkedId ->
            val selectedButton = view.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            updateSegmentedControl(selectedButton)
            when (checkedId) {
                R.id.btn_day -> updateGraphFor("day")
                R.id.btn_week -> updateGraphFor("week")
                R.id.btn_month -> updateGraphFor("month")
                R.id.btn_year -> updateGraphFor("year")
            }
        }

        if (savedInstanceState == null) {
            view.findViewById<RadioButton>(R.id.btn_week).isChecked = true
        }
    }

    private fun updateSegmentedControl(selectedButton: RadioButton) {
        for (i in 0 until segmentedControl.childCount) {
            val button = segmentedControl.getChildAt(i) as RadioButton
            val isSelected = button.id == selectedButton.id
            button.setBackgroundResource(if (isSelected) R.drawable.segment_button_selector else android.R.color.transparent)
            button.setTextColor(ContextCompat.getColor(requireContext(), if (isSelected) R.color.white else R.color.black))
        }
    }

    private fun fetchAllVerifiedUsers() {
        db.collection("users").whereEqualTo("verified", true).get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || snapshot == null) return@addOnSuccessListener
                allVerifiedUsers = snapshot.documents.filter { doc -> !(doc.getBoolean("admin") ?: false) }
                tvTotalUsers.text = allVerifiedUsers.size.toString()
                updateGraphFor("week") // Default view
            }.addOnFailureListener { e ->
                if (!isAdded) return@addOnFailureListener
                Log.e("DashboardFragment", "Error fetching all users: ", e)
                tvTotalUsers.text = "0"
                Toast.makeText(context, "Error fetching data: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun updateGraphFor(type: String) {
        val timeZone = TimeZone.getTimeZone("Asia/Singapore")
        val cal = Calendar.getInstance(timeZone).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val startDate = when (type) {
            "day" -> cal.apply { add(Calendar.DATE, -6) }.time
            "week" -> cal.apply {
                add(Calendar.WEEK_OF_YEAR, -4)
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }.time
            "month" -> cal.apply { add(Calendar.MONTH, -11) }.time
            "year" -> cal.apply { add(Calendar.YEAR, -4) }.time
            else -> Date()
        }

        val usersForGraph = allVerifiedUsers.filter {
            val accountCreated = it.getDate("accountCreated")
            accountCreated != null && !accountCreated.before(startDate)
        }

        val userCounts = processRegistrations(usersForGraph, type)
        setupLineChart(userCounts, type)
    }

    private fun processRegistrations(docs: List<DocumentSnapshot>, type: String): Map<String, Int> {
        val userCounts = mutableMapOf<String, Int>()
        val timeZone = TimeZone.getTimeZone("Asia/Singapore")
        val sdf = SimpleDateFormat(getSdfPattern(type), Locale.US).apply { this.timeZone = timeZone }

        for (doc in docs) {
            val accountCreated = doc.getDate("accountCreated") ?: continue
            val cal = Calendar.getInstance(timeZone).apply { time = accountCreated }

            val key = if (type == "week") {
                // *** RELIABLE WEEK GROUPING ***
                cal.firstDayOfWeek = Calendar.MONDAY
                val offset = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                cal.add(Calendar.DATE, -offset)
                sdf.format(cal.time)
            } else {
                sdf.format(accountCreated)
            }
            userCounts[key] = (userCounts.getOrDefault(key, 0)) + 1
        }
        return userCounts
    }

    private fun setupLineChart(userCounts: Map<String, Int>, type: String) {
        val entries = mutableListOf<Entry>()
        val labels = mutableListOf<String>()
        val timeZone = TimeZone.getTimeZone("Asia/Singapore")
        val cal = Calendar.getInstance(timeZone)
        val sdf = SimpleDateFormat(getSdfPattern(type), Locale.US).apply { this.timeZone = timeZone }

        val (iterations, calendarField) = when (type) {
            "day" -> Pair(7, Calendar.DATE)
            "week" -> Pair(5, Calendar.WEEK_OF_YEAR)
            "month" -> Pair(12, Calendar.MONTH)
            "year" -> Pair(5, Calendar.YEAR)
            else -> return
        }
        
        val originalCal = cal.clone() as Calendar
        originalCal.add(calendarField, -(iterations - 1))

        for (i in 0 until iterations) {
             if (type == "week") {
                originalCal.firstDayOfWeek = Calendar.MONDAY
                originalCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
            val label = sdf.format(originalCal.time)
            labels.add(label)
            entries.add(Entry(i.toFloat(), userCounts[label]?.toFloat() ?: 0f))
            originalCal.add(calendarField, 1)
        }

        if (entries.all { it.y == 0f }) {
            lineChart.clear()
            lineChart.setNoDataText("No chart data available for the selected period.")
            lineChart.invalidate()
            return
        }

        val dataSet = LineDataSet(entries, "Registered & Verified Users")
        dataSet.color = ContextCompat.getColor(requireContext(), R.color.black)
        dataSet.valueTextSize = 10f
        dataSet.setDrawCircles(true)
        dataSet.setCircleColor(ContextCompat.getColor(requireContext(), R.color.black))
        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = value.toInt().toString()
        }

        lineChart.data = LineData(dataSet)
        lineChart.xAxis.apply {
            granularity = 1f
            labelCount = labels.size
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index in labels.indices) labels[index] else ""
                }
            }
            setDrawGridLines(false)
            position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        }
        lineChart.axisLeft.apply {
            granularity = 1f
            axisMinimum = 0f
            setDrawGridLines(false)
        }
        lineChart.axisRight.isEnabled = false
        lineChart.description.isEnabled = false
        lineChart.invalidate()
    }

    private fun getSdfPattern(type: String): String {
        return when (type) {
            "day" -> "EEE"
            "week" -> "MMM d"
            "month" -> "MMM"
            "year" -> "yyyy"
            else -> ""
        }
    }
}
