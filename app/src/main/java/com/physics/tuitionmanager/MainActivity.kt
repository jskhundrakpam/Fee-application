package com.physics.tuitionmanager

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var store: DataStore
    private lateinit var prefs: android.content.SharedPreferences

    private val createDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(store.exportJson().toByteArray())
                }
                prefs.edit().putString("lastBackupDate", nowIso()).apply()
                toast("✅ Backup saved!")
                renderBackup()
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
        }
    }

    private val openDocLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val json = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (json != null) {
                    confirm("Replace all current data with this backup?") {
                        try {
                            store.importJson(json)
                            toast("✅ Restored!")
                            refreshAll()
                        } catch (e: Exception) { toast("Invalid backup file.") }
                    }
                }
            } catch (e: Exception) { toast("Read failed: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = DataStore(this)
        prefs = getSharedPreferences("physics_data", MODE_PRIVATE)

        setupTabs()
        setupBatches()
        setupStudents()
        setupAttendance()
        setupFees()
        setupBackup()

        refreshAll()
        checkWeeklyBackupOnOpen()
        BackupWorker.schedule(this)
    }

    // ================= TABS =================
    private fun setupTabs() {
        val pairs = listOf(
            R.id.tabBatches to R.id.sectionBatches,
            R.id.tabStudents to R.id.sectionStudents,
            R.id.tabAttendance to R.id.sectionAttendance,
            R.id.tabFees to R.id.sectionFees,
            R.id.tabBackup to R.id.sectionBackup
        )
        pairs.forEach { (tabId, sectionId) ->
            findViewById<View>(tabId).setOnClickListener {
                pairs.forEach { (t, s) ->
                    findViewById<View>(s).visibility =
                        if (s == sectionId) View.VISIBLE else View.GONE
                }
                when (sectionId) {
                    R.id.sectionBatches -> renderBatches()
                    R.id.sectionStudents -> renderStudents()
                    R.id.sectionAttendance -> renderAttendance()
                    R.id.sectionFees -> renderFees()
                    R.id.sectionBackup -> renderBackup()
                }
            }
        }
    }

    // ================= BATCHES =================
    private fun setupBatches() {
        findViewById<Button>(R.id.addBatchBtn).setOnClickListener {
            val name = findViewById<EditText>(R.id.batchNameInput).text.toString().trim()
            if (name.isEmpty()) { toast("Enter batch name"); return@setOnClickListener }
            store.addBatch(name)
            findViewById<EditText>(R.id.batchNameInput).text.clear()
            renderBatches(); renderStudents(); renderAttendance()
        }
    }

    private fun renderBatches() {
        val container = findViewById<LinearLayout>(R.id.batchListContainer)
        container.removeAllViews()
        if (store.batches.isEmpty()) {
            container.addView(emptyText("No batches yet."))
            return
        }
        store.batches.forEach { b ->
            val count = store.students.count { it.batchId == b.id }
            val row = layoutInflater.inflate(R.layout.row_item, container, false)
            row.findViewById<TextView>(R.id.rowTitle).text = b.name
            row.findViewById<TextView>(R.id.rowSubtitle).text = "$count student(s)"
            row.findViewById<Button>(R.id.rowAction).apply {
                text = "Delete"
                setOnClickListener {
                    confirm("Delete this batch? Students become unassigned.") {
                        store.deleteBatch(b.id)
                        renderBatches(); renderStudents(); renderAttendance()
                    }
                }
            }
            container.addView(row)
        }
    }

    // ================= STUDENTS =================
    private fun setupStudents() {
        findViewById<Button>(R.id.addStudentBtn).setOnClickListener {
            val name = findViewById<EditText>(R.id.studentNameInput).text.toString().trim()
            val phone = findViewById<EditText>(R.id.studentPhoneInput).text.toString().trim()
            val batchId = getSelectedBatchId(findViewById(R.id.studentBatchSpinner))
            if (name.isEmpty()) { toast("Enter student name"); return@setOnClickListener }
            store.addStudent(name, batchId, phone)
            findViewById<EditText>(R.id.studentNameInput).text.clear()
            findViewById<EditText>(R.id.studentPhoneInput).text.clear()
            renderStudents(); renderAttendance(); renderFees()
        }
    }

    private fun renderStudents() {
        val spinner = findViewById<Spinner>(R.id.studentBatchSpinner)
        val names = mutableListOf("-- No batch --")
        names.addAll(store.batches.map { it.name })
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val container = findViewById<LinearLayout>(R.id.studentListContainer)
        container.removeAllViews()
        if (store.students.isEmpty()) {
            container.addView(emptyText("No students yet."))
            return
        }
        store.students.forEach { s ->
            val batch = store.batches.find { it.id == s.batchId }
            val row = layoutInflater.inflate(R.layout.row_item, container, false)
            row.findViewById<TextView>(R.id.rowTitle).text = s.name
            row.findViewById<TextView>(R.id.rowSubtitle).text =
                (batch?.name ?: "Unassigned") + (if (s.phone.isNotEmpty()) " • ${s.phone}" else "")
            row.findViewById<Button>(R.id.rowAction).apply {
                text = "Delete"
                setOnClickListener {
                    confirm("Delete student and all records?") {
                        store.deleteStudent(s.id)
                        renderStudents(); renderAttendance(); renderFees()
                    }
                }
            }
            container.addView(row)
        }
    }

    private fun getSelectedBatchId(spinner: Spinner): String {
        val pos = spinner.selectedItemPosition
        if (pos <= 0 || pos - 1 >= store.batches.size) return ""
        return store.batches[pos - 1].id
    }

    // ================= ATTENDANCE =================
    private fun setupAttendance() {
        val dateInput = findViewById<EditText>(R.id.attendanceDateInput)
        setupDatePicker(dateInput) { renderAttendance() }
    }

    private fun renderAttendance() {
        val spinner = findViewById<Spinner>(R.id.attendanceBatchSpinner)
        val prevPos = spinner.selectedItemPosition
        val batchNames = mutableListOf("All Batches")
        batchNames.addAll(store.batches.map { it.name })
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, batchNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (prevPos in 0 until batchNames.size) spinner.setSelection(prevPos)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                renderAttendanceList()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val date = findViewById<EditText>(R.id.attendanceDateInput)
        if (date.text.isNullOrEmpty()) date.setText(todayStr())
        renderAttendanceList()
    }

    private fun renderAttendanceList() {
        val container = findViewById<LinearLayout>(R.id.attendanceListContainer)
        container.removeAllViews()
        val pos = findViewById<Spinner>(R.id.attendanceBatchSpinner).selectedItemPosition
        val batchFilter = if (pos <= 0) null else store.batches.getOrNull(pos - 1)?.id
        val date = findViewById<EditText>(R.id.attendanceDateInput).text.toString()
        val list = if (batchFilter == null) store.students else store.students.filter { it.batchId == batchFilter }
        if (list.isEmpty()) { container.addView(emptyText("No students.")); return }

        list.forEach { s ->
            val rec = store.attendance.find { it.studentId == s.id && it.date == date }
            val row = layoutInflater.inflate(R.layout.row_attendance, container, false)
            row.findViewById<TextView>(R.id.rowTitle).text = s.name
            val p = row.findViewById<Button>(R.id.presentBtn)
            val a = row.findViewById<Button>(R.id.absentBtn)
            p.alpha = if (rec?.status == "present") 1f else 0.4f
            a.alpha = if (rec?.status == "absent") 1f else 0.4f
            p.setOnClickListener { store.setAttendance(s.id, date, "present"); renderAttendanceList() }
            a.setOnClickListener { store.setAttendance(s.id, date, "absent"); renderAttendanceList() }
            container.addView(row)
        }
    }

    // ================= FEES =================
    private fun setupFees() {
        setupDatePicker(findViewById(R.id.feeDateInput)) { }
        findViewById<Button>(R.id.addFeeBtn).setOnClickListener {
            val studentId = getSelectedStudentId(findViewById(R.id.feeStudentSpinner))
            val amountStr = findViewById<EditText>(R.id.feeAmountInput).text.toString()
            val date = findViewById<EditText>(R.id.feeDateInput).text.toString()
            if (studentId.isEmpty() || amountStr.isEmpty() || date.isEmpty()) {
                toast("Fill all fields"); return@setOnClickListener
            }
            val amt = amountStr.toDoubleOrNull() ?: return@setOnClickListener toast("Invalid amount")
            store.addFee(studentId, amt, date)
            findViewById<EditText>(R.id.feeAmountInput).text.clear()
            renderFees()
        }
    }

    private fun getSelectedStudentId(spinner: Spinner): String {
        val pos = spinner.selectedItemPosition
        if (pos <= 0 || pos - 1 >= store.students.size) return ""
        return store.students[pos - 1].id
    }

    private fun renderFees() {
        val spinner = findViewById<Spinner>(R.id.feeStudentSpinner)
        val prevPos = spinner.selectedItemPosition
        val names = mutableListOf("-- Select Student --")
        names.addAll(store.students.map { it.name })
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (prevPos in 0 until names.size) spinner.setSelection(prevPos)

        val container = findViewById<LinearLayout>(R.id.feeListContainer)
        container.removeAllViews()
        if (store.fees.isEmpty()) { container.addView(emptyText("No fee records.")); return }
        store.fees.reversed().forEach { f ->
            val s = store.students.find { it.id == f.studentId }
            val row = layoutInflater.inflate(R.layout.row_item, container, false)
            row.findViewById<TextView>(R.id.rowTitle).text = s?.name ?: "Unknown"
            row.findViewById<TextView>(R.id.rowSubtitle).text = "₹${f.amount} • ${f.date}"
            row.findViewById<Button>(R.id.rowAction).apply {
                text = "Delete"
                setOnClickListener {
                    confirm("Delete this fee record?") {
                        store.deleteFee(f.id); renderFees()
                    }
                }
            }
            container.addView(row)
        }
    }

    // ================= BACKUP =================
    private fun setupBackup() {
        findViewById<Button>(R.id.saveBackupBtn).setOnClickListener {
            val ds = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            createDocLauncher.launch("physics-backup-$ds.json")
        }
        findViewById<Button>(R.id.shareBackupBtn).setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Physics Tuition Backup")
                putExtra(Intent.EXTRA_TEXT, store.exportJson())
            }
            startActivity(Intent.createChooser(i, "Share backup"))
            prefs.edit().putString("lastBackupDate", nowIso()).apply()
        }
        findViewById<Button>(R.id.importBackupBtn).setOnClickListener {
            openDocLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun renderBackup() {
        val last = prefs.getString("lastBackupDate", null)
        val days = daysSince(last)
        val status = findViewById<TextView>(R.id.backupStatusText)
        when {
            last == null -> {
                status.text = "⚠️ No external backup yet. Save one now!"
                status.setTextColor(0xFFD32F2F.toInt())
            }
            days >= 7 -> {
                status.text = "⚠️ Last backup: $days days ago. Save now!"
                status.setTextColor(0xFFD32F2F.toInt())
            }
            else -> {
                status.text = "✅ Last backup: ${if (days == 0) "today" else "$days day(s) ago"}"
                status.setTextColor(0xFF2E7D32.toInt())
            }
        }
        val sizeKb = store.exportJson().toByteArray().size / 1024.0
        findViewById<TextView>(R.id.backupDataSizeText).text =
            String.format(Locale.US, "%.1f KB • %d students • %d attendance • %d fees",
                sizeKb, store.students.size, store.attendance.size, store.fees.size)

        val container = findViewById<LinearLayout>(R.id.snapshotListContainer)
        container.removeAllViews()
        val dir = File(filesDir, "backups")
        val files = dir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
        if (files.isEmpty()) { container.addView(emptyText("No auto-snapshots yet.")); return }
        files.forEach { f ->
            val row = layoutInflater.inflate(R.layout.row_item, container, false)
            row.findViewById<TextView>(R.id.rowTitle).text = f.name.removePrefix("snapshot_").removeSuffix(".json")
            row.findViewById<TextView>(R.id.rowSubtitle).text = "${f.length() / 1024} KB"
            row.findViewById<Button>(R.id.rowAction).apply {
                text = "Restore"
                setBackgroundColor(0xFF00796B.toInt())
                setOnClickListener {
                    confirm("Restore this snapshot?") {
                        try {
                            store.importJson(f.readText()); refreshAll(); toast("✅ Restored")
                        } catch (e: Exception) { toast("Failed") }
                    }
                }
            }
            container.addView(row)
        }
    }

    private fun checkWeeklyBackupOnOpen() {
        val lastInternal = prefs.getString("lastInternalBackup", null)
        if ((daysSince(lastInternal) >= 7) &&
            (store.batches.isNotEmpty() || store.students.isNotEmpty())) {
            val dir = File(filesDir, "backups").apply { if (!exists()) mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "snapshot_$ts.json").writeText(store.exportJson())
            val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.size > 8) files.take(files.size - 8).forEach { it.delete() }
            prefs.edit().putString("lastInternalBackup", nowIso()).apply()
        }
        if (daysSince(prefs.getString("lastBackupDate", null)) >= 7) {
            findViewById<View>(R.id.backupBanner).visibility = View.VISIBLE
        }
    }

    // ================= HELPERS =================
    private fun setupDatePicker(et: EditText, onChange: () -> Unit) {
        et.isFocusable = false
        et.isClickable = true
        if (et.text.isNullOrEmpty()) et.setText(todayStr())
        et.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                et.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
                onChange()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
    }
    private fun todayStr() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun nowIso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    private fun daysSince(iso: String?): Int {
        if (iso == null) return Int.MAX_VALUE
        return try {
            val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)
            ((System.currentTimeMillis() - (d?.time ?: 0L)) / (1000L * 60 * 60 * 24)).toInt()
        } catch (e: Exception) { Int.MAX_VALUE }
    }
    private fun confirm(msg: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun emptyText(m: String) = TextView(this).apply {
        text = m; setPadding(24, 24, 24, 24); setTextColor(0xFF777777.toInt())
    }
    private fun refreshAll() {
        renderBatches(); renderStudents(); renderAttendance(); renderFees(); renderBackup()
    }
}
