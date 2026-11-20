package dev.tvsopen.diagnostic

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.tvsopen.log.Level
import dev.tvsopen.log.Log
import dev.tvsopen.nav.NavInstruction
import dev.tvsopen.protocol.ClusterFrames
import dev.tvsopen.protocol.Command
import dev.tvsopen.protocol.OutboundId
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.VehicleModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private lateinit var journal: Journal
    private lateinit var link: BenchLink
    private lateinit var status: TextView
    private lateinit var captureStatus: TextView
    private lateinit var body: ScrollView
    private val screens = linkedMapOf<String, LinearLayout>()
    private val tabs = linkedMapOf<String, Button>()
    private var activeTab = "Connection"
    private val logViews = mutableMapOf<String, TextView>()
    private val autoFollow = mutableMapOf<String, CheckBox>()
    private var testContext = "No test selected"
    private var exportRunning = false
    private var reviewOnly = false
    private var history: Journal.Page? = null
    private var historyLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        journal = Journal(java.io.File(filesDir, "capture.txt"))
        Log.captureFrames = false
        Log.recordObserver = { record ->
            journal.add(if (record.level >= Level.WARN) "WARN" else "GATT",
                "${record.tag} ${record.message}", record.atMillis)
        }
        link = BenchLink(applicationContext, scope, journal)
        val root = column()
        root.setPadding(dp(12), dp(8), dp(12), dp(8))
        setContentView(root)
        root.label("TVS Diagnostic", 23f).setTypeface(null, Typeface.BOLD)
        status = root.label("Disconnected")
        captureStatus = root.label("Recording locally")
        val strip = HorizontalScrollView(this)
        val choices = LinearLayout(this)
        strip.addView(choices)
        root.addView(strip)
        for (name in listOf("Connection", "Navigation", "Calls", "Music", "Coming soon", "Log", "Incoming")) {
            tabs[name] = choices.action(name) { show(name) }
            screens[name] = column()
        }
        body = ScrollView(this).apply { isFillViewport = true }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        connection(screens.getValue("Connection"))
        navigation(screens.getValue("Navigation"))
        calls(screens.getValue("Calls"))
        music(screens.getValue("Music"))
        screens.getValue("Coming soon").label("Cricket scores and weather")
        logScreen("Log")
        logScreen("Incoming")
        show("Connection")
        scope.launch { link.observe { status.text = it } }
        scope.launch {
            while (true) {
                updateLog()
                captureStatus.text = journal.error ?: "Recording locally"
                delay(400)
            }
        }
    }

    private fun connection(panel: LinearLayout) {
        panel.label("Disconnect the regular companion before connecting here. Keep this app open during the test.")
        val models = listOf(VehicleModel.RAIDER_125, VehicleModel.NTORQ_125_RACE, VehicleModel.NTORQ,
            VehicleModel.RAIDER_180, VehicleModel.JUPITER_110, VehicleModel.JUPITER_125)
        val model = panel.select(models.map { it.displayName })
        val scanStatus = panel.label("Scan to find a TVS cluster.")
        panel.action("Scan for clusters") {
            if (!link.disconnected) {
                toast("Disconnect the current cluster first")
            } else if (permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                startScan()
            } else {
                requestPermissions(permissions(), 1)
            }
        }
        panel.action("Disconnect") {
            scope.launch {
                journal.add("ACTION", "Manual disconnect")
                link.disconnect()
            }
        }
        val results = column()
        panel.addView(results)
        scope.launch {
            link.scanner.state.collect { scan ->
                scanStatus.text = scan.error ?: if (scan.scanning) "Scanning by cluster name and advertised service…" else
                    "${scan.devices.count { it.isSmartXonnect }} cluster(s) found"
                results.removeAllViews()
                for (device in scan.devices.filter { it.isSmartXonnect }) {
                    results.action("${device.name ?: "SmartXonnect"} · ${device.rssi} dBm") {
                        link.connect(device.address, models[model.selectedItemPosition])
                    }
                }
            }
        }
        panel.noteForm()
    }

    private fun navigation(panel: LinearLayout) {
        panel.label("Manual route. ETA minutes goes into the packet. Trip duration and arrival time are saved with your observation.")
        val road = panel.field("Road label", "Example Road")
        val next = panel.field("Metres to next junction", "600", numeric = true)
        val total = panel.field("Remaining trip metres", "2320", numeric = true)
        val eta = panel.field("ETA minutes sent to cluster", "3", numeric = true)
        val duration = panel.field("Full trip duration in minutes", "8", numeric = true)
        val arrival = panel.field("Arrival time (e.g. 11:40)")
        panel.check("Raider primary row only") { link.primaryOnly = it }
        panel.check("Raider static text") { link.stableText = it }
        var symbol = 7
        val chosen = panel.label("Selected symbol byte: 7")
        var running = false
        fun apply(): Boolean {
            if (!link.ready) {
                toast("Wait for Ready to write")
                return false
            }
            val metres = next.number(65535) ?: return false
            val remaining = total.number(16777215) ?: return false
            val minutes = eta.number(65535) ?: return false
            val tripMinutes = duration.number(Int.MAX_VALUE) ?: return false
            testContext = "NAV symbol=$symbol next=${metres}m total=${remaining}m ETA=${minutes}min"
            journal.add("ACTION", "$testContext duration=${tripMinutes}min arrival=${arrival.value()} road=${road.value()}")
            link.navigation(NavInstruction("", road.value(), metres, minutes, symbol, "manual", remaining))
            return true
        }
        val symbols = LinearLayout(this)
        panel.addView(HorizontalScrollView(this).apply { addView(symbols) })
        for ((id, name) in listOf(0 to "Left", 3 to "Right", 7 to "Straight", 6 to "U-turn", 8 to "Destination", 9 to "Exit")) {
            symbols.action("$name · $id") {
                symbol = id
                chosen.text = "Selected symbol byte: $id"
                if (running && link.ready) apply()
            }
        }
        panel.action("Navigation on / apply values") { running = apply() }
        panel.action("Navigation off") {
            running = false
            testContext = "Navigation off"
            journal.add("ACTION", testContext)
            link.stopNavigation()
        }
        panel.noteForm()
    }

    private fun calls(panel: LinearLayout) {
        panel.label("Simulated calls use the selected packet format. Cluster display and clearing are hardware tests.")
        val number = panel.field("Test phone number", "5550100")
        val name = panel.field("Test contact name", "Test Caller")
        val country = panel.field("Country code for split frames")
        val format = panel.select(listOf("Companion model format", "Experimental split 0x43", "Experimental single row 0x43"))
        var activeFormat: Int? = null
        panel.action("Incoming call") {
            if (!link.ready) return@action toast("Wait for Ready to write")
            val driver = link.driver ?: return@action toast("Connect a cluster first")
            val num = number.value()
            val contact = name.value().ifBlank { null }
            if (num.isBlank() && contact == null) return@action toast("Enter a test caller")
            val packets = when (format.selectedItemPosition) {
                1 -> ClusterFrames.callerFrames(num, country.value(), contact)
                2 -> listOf(PacketBuilder.callerInfo(num, contact))
                else -> driver.buildCallerFrames(num, country.value().ifBlank { null }, contact)
            }
            activeFormat = format.selectedItemPosition
            transmit("Incoming call (${format.selectedItem})", packets)
        }
        panel.action("End call / try clearing display") {
            val clear = when (activeFormat ?: format.selectedItemPosition) {
                1 -> null
                2 -> PacketBuilder.callerInfo("", null, Command.CALL_IDLE)
                else -> link.driver?.buildCallerInfo("", null, Command.CALL_IDLE)
            }
            activeFormat = null
            if (clear != null) transmit("End call · blank caller row", listOf(clear))
            else {
                journal.add("NOTE", "End call marked; split-frame clear is unknown. Observe cluster timeout.")
                toast("Marked call end. Observe whether the cluster display times out.")
            }
        }
        val text = panel.field("Test message", "TEST MESSAGE")
        panel.action("Send test message") {
            if (text.value().isBlank()) return@action toast("Enter a test message")
            val packet = link.driver?.buildSms(text.value()) ?: PacketBuilder.textRow(OutboundId.SMS, text.value())
            transmit("Test message", listOf(packet))
        }
        panel.noteForm()
    }

    private fun music(panel: LinearLayout) {
        panel.label("Dummy playback sends experimental metadata and state. The two state values and title mapping can be tested independently.")
        val titleId = panel.select(listOf("0x61 candidate title", "0x62 candidate title", "0x65 candidate title"))
        val playing = panel.field("Play state byte (decimal)", "1", numeric = true)
        val paused = panel.field("Pause state byte (decimal)", "0", numeric = true)
        val trackLabel = panel.label("Track 1 · Paused")
        var track = 1
        var playingNow = false
        fun sendTrack(action: String, selectedTrack: Int, play: Boolean, metadata: Boolean) {
            if (!link.ready) return toast("Wait for Ready to write")
            val state = (if (play) playing else paused).number(255) ?: return
            val id = listOf(0x61, 0x62, 0x65)[titleId.selectedItemPosition]
            val frames = buildList {
                if (metadata) add(ClusterFrames.mediaMeta(id, "Test Track $selectedTrack"))
                add(ClusterFrames.playPauseState(state))
            }
            track = selectedTrack
            playingNow = play
            trackLabel.text = "Track $track · ${if (play) "Playing" else "Paused"}"
            transmit("Music $action track=$track metadataId=${if (metadata) "0x${id.toString(16)}" else "none"} state=$state", frames)
        }
        panel.action("Play") { sendTrack("play", track, true, true) }
        panel.action("Pause") { sendTrack("pause", track, false, false) }
        panel.action("Next") { sendTrack("next", track % 3 + 1, playingNow, true) }
        panel.action("Previous") { sendTrack("previous", (track + 1) % 3 + 1, playingNow, true) }
        panel.noteForm()
    }

    private fun logScreen(name: String) {
        val panel = screens.getValue(name)
        panel.label(if (name == "Incoming") "Recent cluster packets. Add a note before or after pressing a physical button."
            else "Recent capture. Export saves the full history, including earlier sessions.")
        panel.action("Export to Downloads") { exportCapture() }
        panel.noteForm()
        if (name == "Log") {
            val controls = LinearLayout(this)
            panel.addView(controls)
            controls.action("Saved history / older") { loadOlder() }
            controls.action("Back to live") {
                history = null
                updateLog()
            }
        }
        if (name == "Incoming") panel.check("Hide routine telemetry") {
            reviewOnly = it
            updateLog()
        }
        autoFollow[name] = panel.check("Follow latest").apply { isChecked = true }
        logViews[name] = panel.label("", 12f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
    }

    private fun LinearLayout.noteForm() {
        val note = field("Observation or upcoming button press")
        val important = check("Mark important")
        action("Save note") {
            val value = note.value()
            if (value.isBlank()) return@action toast("Enter an observation first")
            if (value.length > 1000) return@action toast("Keep observations under 1000 characters")
            journal.note(value, important.isChecked, testContext)
            note.text.clear()
            toast("Note recorded")
            updateLog()
        }
    }

    private fun transmit(label: String, packets: List<ByteArray>) {
        if (!link.ready) return toast("Wait for Ready to write")
        testContext = label
        scope.launch {
            commands.withLock { link.send(label, packets) }
        }
    }

    private fun show(name: String) {
        activeTab = name
        tabs.forEach { (tab, button) -> button.isSelected = tab == name; button.isEnabled = tab != name }
        body.removeAllViews()
        body.addView(screens.getValue(name))
        body.scrollTo(0, 0)
        updateLog()
    }

    private fun updateLog() {
        val view = logViews[activeTab] ?: return
        val saved = history.takeIf { activeTab == "Log" }
        val text = if (saved == null) journal.tail(activeTab == "Incoming", activeTab == "Incoming" && reviewOnly)
        else "Saved capture · bytes ${saved.start}–${saved.end}\n\n${saved.text}"
        if (view.text.toString() == text) return
        view.text = text
        if (saved == null && autoFollow[activeTab]?.isChecked == true) {
            body.post { if (currentFocus !is EditText) body.scrollTo(0, view.bottom) }
        }
    }

    private fun loadOlder() {
        if (historyLoading) return
        val before = history?.start
        if (before == 0L) return toast("Beginning of saved capture")
        historyLoading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching { journal.history(before) } }
                result.onSuccess {
                    history = it
                    updateLog()
                    if (activeTab == "Log") body.scrollTo(0, 0)
                }.onFailure { toast("Could not read saved capture: ${it.message}") }
            } finally {
                historyLoading = false
            }
        }
    }

    private fun exportCapture() {
        if (exportRunning) return
        val name = "TVS-Diagnostic-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.txt"
        journal.add("ACTION", "Export requested")
        if (Build.VERSION.SDK_INT < 29) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, name)
            }, 2)
            return
        }
        runExport {
            val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })) { "Could not create Downloads entry" }
            try {
                writeExport(uri)
                check(contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null) == 1) { "Could not publish Downloads entry" }
            } catch (failure: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw failure
            }
            "Saved to Downloads as $name"
        }
    }

    private fun writeExport(uri: android.net.Uri) {
        checkNotNull(contentResolver.openOutputStream(uri, "w")) { "Could not open export destination" }
            .use(journal::export)
    }

    private fun runExport(save: () -> String) {
        exportRunning = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching(save) }
                result.onSuccess(::toast).onFailure {
                    journal.add("WARN", "Export failed: ${it.message}")
                    toast("Export failed: ${it.message}")
                }
            } finally {
                exportRunning = false
            }
        }
    }

    @Deprecated("Used for the Android 8/9 document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) data?.data?.let { uri ->
            runExport { writeExport(uri); "Capture exported" }
        }
    }

    private fun permissions() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun startScan() {
        journal.add("ACTION", "Scan started with the companion scanner")
        link.scanner.start(onlySmartXonnect = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else toast("Bluetooth scan permissions required")
        }
    }

    override fun onStop() {
        super.onStop()
        journal.flush()
    }

    override fun onDestroy() {
        scope.cancel()
        // Close the GATT client even if a response write is still settling.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                link.disconnect()
            } finally {
                Log.recordObserver = null
                journal.close()
            }
        }
        super.onDestroy()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }

    private fun LinearLayout.label(value: String, size: Float = 15f) = TextView(this@MainActivity).apply {
        text = value
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
        this@label.addView(this)
    }

    private fun LinearLayout.action(value: String, clicked: () -> Unit) = Button(this@MainActivity).apply {
        text = value
        isAllCaps = false
        setOnClickListener { clicked() }
        this@action.addView(this)
    }

    private fun LinearLayout.field(title: String, initial: String = "", numeric: Boolean = false): EditText {
        val caption = label(title)
        val field = EditText(this@MainActivity).apply {
            id = View.generateViewId()
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = title
            setText(initial)
        }
        caption.labelFor = field.id
        addView(field)
        return field
    }

    private fun LinearLayout.check(value: String, changed: (Boolean) -> Unit = {}) = CheckBox(this@MainActivity).apply {
        text = value
        setOnCheckedChangeListener { _, checked -> changed(checked) }
        this@check.addView(this)
    }

    private fun LinearLayout.select(choices: List<String>) = Spinner(this@MainActivity).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices)
        this@select.addView(this)
    }

    private fun EditText.value() = text.toString().trim()

    private fun EditText.number(max: Int): Int? {
        val value = value().toIntOrNull()
        if (value == null || value !in 0..max) {
            error = "Enter a value from 0 to $max"
            return null
        }
        return value
    }
}
