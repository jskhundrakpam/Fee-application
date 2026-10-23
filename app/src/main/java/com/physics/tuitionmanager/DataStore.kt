package com.physics.tuitionmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class Batch(val id: String, val name: String)
data class Student(val id: String, val name: String, var batchId: String, val phone: String)
data class AttendanceRecord(val id: String, val studentId: String, val date: String, var status: String)
data class FeeRecord(val id: String, val studentId: String, val amount: Double, val date: String)

class DataStore(context: Context) {
    private val prefs = context.getSharedPreferences("physics_data", Context.MODE_PRIVATE)

    var batches: MutableList<Batch> = mutableListOf()
    var students: MutableList<Student> = mutableListOf()
    var attendance: MutableList<AttendanceRecord> = mutableListOf()
    var fees: MutableList<FeeRecord> = mutableListOf()

    init { load() }

    private fun load() {
        batches = parseBatches(prefs.getString("batches", "[]")!!)
        students = parseStudents(prefs.getString("students", "[]")!!)
        attendance = parseAttendance(prefs.getString("attendance", "[]")!!)
        fees = parseFees(prefs.getString("fees", "[]")!!)
    }

    fun save() {
        prefs.edit()
            .putString("batches", batchesToJson(batches).toString())
            .putString("students", studentsToJson(students).toString())
            .putString("attendance", attendanceToJson(attendance).toString())
            .putString("fees", feesToJson(fees).toString())
            .apply()
    }

    fun addBatch(name: String) { batches.add(Batch(uid(), name)); save() }
    fun deleteBatch(id: String) {
        batches.removeAll { it.id == id }
        students.forEach { if (it.batchId == id) it.batchId = "" }
        save()
    }
    fun addStudent(name: String, batchId: String, phone: String) {
        students.add(Student(uid(), name, batchId, phone)); save()
    }
    fun deleteStudent(id: String) {
        students.removeAll { it.id == id }
        attendance.removeAll { it.studentId == id }
        fees.removeAll { it.studentId == id }
        save()
    }
    fun setAttendance(studentId: String, date: String, status: String) {
        val ex = attendance.find { it.studentId == studentId && it.date == date }
        if (ex != null) ex.status = status
        else attendance.add(AttendanceRecord(uid(), studentId, date, status))
        save()
    }
    fun addFee(studentId: String, amount: Double, date: String) {
        fees.add(FeeRecord(uid(), studentId, amount, date)); save()
    }
    fun deleteFee(id: String) { fees.removeAll { it.id == id }; save() }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        root.put("batches", batchesToJson(batches))
        root.put("students", studentsToJson(students))
        root.put("attendance", attendanceToJson(attendance))
        root.put("fees", feesToJson(fees))
        return root.toString(2)
    }

    fun importJson(json: String) {
        val root = JSONObject(json)
        batches = parseBatches(root.optJSONArray("batches")?.toString() ?: "[]")
        students = parseStudents(root.optJSONArray("students")?.toString() ?: "[]")
        attendance = parseAttendance(root.optJSONArray("attendance")?.toString() ?: "[]")
        fees = parseFees(root.optJSONArray("fees")?.toString() ?: "[]")
        save()
    }

    private fun uid() = System.currentTimeMillis().toString() + (1000..9999).random()

    private fun batchesToJson(list: List<Batch>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply { put("id", it.id); put("name", it.name) }) }
        return a
    }
    private fun studentsToJson(list: List<Student>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply {
            put("id", it.id); put("name", it.name); put("batchId", it.batchId); put("phone", it.phone)
        }) }
        return a
    }
    private fun attendanceToJson(list: List<AttendanceRecord>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply {
            put("id", it.id); put("studentId", it.studentId); put("date", it.date); put("status", it.status)
        }) }
        return a
    }
    private fun feesToJson(list: List<FeeRecord>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().apply {
            put("id", it.id); put("studentId", it.studentId); put("amount", it.amount); put("date", it.date)
        }) }
        return a
    }
    private fun parseBatches(s: String): MutableList<Batch> {
        val l = mutableListOf<Batch>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(Batch(o.optString("id"), o.optString("name")))
        }
        return l
    }
    private fun parseStudents(s: String): MutableList<Student> {
        val l = mutableListOf<Student>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(Student(o.optString("id"), o.optString("name"), o.optString("batchId"), o.optString("phone")))
        }
        return l
    }
    private fun parseAttendance(s: String): MutableList<AttendanceRecord> {
        val l = mutableListOf<AttendanceRecord>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(AttendanceRecord(o.optString("id"), o.optString("studentId"), o.optString("date"), o.optString("status")))
        }
        return l
    }
    private fun parseFees(s: String): MutableList<FeeRecord> {
        val l = mutableListOf<FeeRecord>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(FeeRecord(o.optString("id"), o.optString("studentId"), o.optDouble("amount", 0.0), o.optString("date")))
        }
        return l
    }
}
