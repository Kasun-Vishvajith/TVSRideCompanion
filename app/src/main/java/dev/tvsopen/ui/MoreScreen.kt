package dev.tvsopen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tvsopen.Nucleus
import dev.tvsopen.nav.MapsNotificationListener
import dev.tvsopen.data.DataExport
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.protocol.Confidence
import dev.tvsopen.protocol.Pictograms
import dev.tvsopen.session.Ride
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.ThemeMode
import dev.tvsopen.ui.theme.ThemePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * More / settings screen.
 *
 * The transparency panel is the important part: it states exactly which
 * protocol facts are verified and which still await hardware confirmation, so
 * a user reading this app knows what they can trust.
 */
@Composable
fun MoreScreen(
    connection: dev.tvsopen.session.ConnectionState1,
    riderName: String?,
    rides: List<Ride>,
    totalRides: Int,
    totalRideDistanceKm: Double,
    onRiderNameChange: (String?) -> Unit,
    notificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val reader by MapsNotificationListener.readerStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("KEEP THE CONNECTION ALIVE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { DozeCard() }

        item {
            Text("APPEARANCE", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item { AppearanceCard() }
        item { ParkingCard() }
        if (connection.model == dev.tvsopen.protocol.VehicleModel.RAIDER_125) {
            item { RaiderNavigationCard() }
        }

        item { RiderNameCard(name = riderName, onNameChange = onRiderNameChange) }

        item {
            Text("RIDES", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            RidesCard(
                rides = rides,
                totalRides = totalRides,
                totalDistanceKm = totalRideDistanceKm,
                connected = connection.state == dev.tvsopen.transport.ConnectionState.CONNECTED,
            )
        }

        item {
            Text("YOUR DATA", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Export rides, settings and the last parked spot as JSON. " +
                            "Everything stays on your device unless you share it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        val json = DataExport.buildJson(ctx)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "TVSRide export")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "Export data"))
                    }) {
                        Text("Export & share")
                    }
                }
            }
        }

        item {
            Text("CONNECTION", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(connection.model.displayName, style = MaterialTheme.typography.titleMedium)
                    // .label, not the enum name: this printed "CONNECTING".
                    Text("State: ${connection.state.label}")
                    Text("BLE writes: ${if (connection.writeReady) "Ready" else "Not ready"}")
                    connection.address?.let { address ->
                        var revealAddress by remember { mutableStateOf(false) }
                        Text(if (revealAddress) "Bluetooth address: $address" else "Bluetooth address hidden for privacy")
                        OutlinedButton(onClick = { revealAddress = !revealAddress }) {
                            Text(if (revealAddress) "Hide address" else "Show private address")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Scan")
                        }
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        item {
            Text("PERMISSIONS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (notificationAccessGranted)
                            "Notification access: granted"
                        else
                            "Notification access: required to read navigation, calls and messages",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (notificationAccessGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                !reader.connected -> "Notification reader: disconnected"
                                reader.guidanceReceived -> "Notification reader: connected; guidance captured"
                                reader.mapsNotificationReceived -> "Notification reader: connected; maps notification received, waiting for readable guidance"
                                else -> "Notification reader: connected; waiting for a maps notification"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Start turn-by-turn navigation in your maps app and check that its navigation notification appears in the notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!reader.connected) {
                            OutlinedButton(onClick = { MapsNotificationListener.ensureConnected(context) }) {
                                Text("Reconnect notification reader")
                            }
                        }
                        if (android.os.Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")) {
                            Text(
                                "Xiaomi / Redmi / POCO: enable Background autostart for TVSRide and set its Battery saver to No restrictions. Menu names vary by MIUI / HyperOS version.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")))
                            }) {
                                Text("Open TVSRide app settings")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenNotificationAccess) {
                        Icon(Icons.Filled.NotificationsActive, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Open notification access")
                    }
                }
            }
        }

        item {
            Text("ALERTS", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            val ctx = LocalContext.current
            val prefs = remember { RidePrefs.load(ctx) }
            var mirrorCalls by remember { mutableStateOf(prefs.mirrorPhoneCalls) }
            var mirrorMessages by remember { mutableStateOf(prefs.mirrorMessages) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (connection.model == dev.tvsopen.protocol.VehicleModel.RAIDER_125) {
                        Text("Raider call/message rendering is unverified. These switches request forwarding; they do not confirm cluster support.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Phone & app calls", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show incoming calls (phone, WhatsApp, Telegram, etc.) on the cluster.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorCalls,
                            onCheckedChange = {
                                mirrorCalls = it
                                prefs.mirrorPhoneCalls = it
                            },
                        )
                    }
                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Messages", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Show sender and app (WhatsApp, Telegram, SMS, etc.). Message text is never sent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = mirrorMessages,
                            onCheckedChange = {
                                mirrorMessages = it
                                prefs.mirrorMessages = it
                            },
                        )
                    }
                }
            }
        }

        item { VehicleEvidenceCard(connection.model) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Science, null)
                    Text("TVSRide", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "No account. No TVS server. Data stays on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun RiderNameCard(name: String?, onNameChange: (String?) -> Unit) {
    var editing by remember(name) { mutableStateOf(name.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Console greeting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Shown by the cluster on ignition. Saving sends it at once when " +
                    "connected, otherwise on the next connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = editing,
                onValueChange = { if (it.length <= 17) editing = it },
                label = { Text("Rider name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${editing.length}/17") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val trimmed = editing.trim().takeIf { it.isNotEmpty() }
                    onNameChange(trimmed)
                    editing = trimmed.orEmpty()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    editing = ""
                    onNameChange(null)
                }) { Text("Clear") }
            }
            Text(
                if (name.isNullOrBlank()) "Cluster shows HELLO RIDER"
                else "Saved: $name — sent now if connected, else on next connection",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}


/**
 * Doze exemption.
 *
 * This is the difference between a working app and one that dies mid-ride. Doze
 * suspends the GATT client within minutes of the screen going off, the
 * presence packet stops, and the cluster drops the link. The working Jupiter
 * companion asks for the same exemption.
 *
 * Android has no runtime dialog for this one — it is granted in system settings
 * or via the request intent — so the card states the state plainly and links
 * out, rather than pretending a button can do it.
 */
@Composable
private fun DozeCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exempt by remember { mutableStateOf(isDozeExempt(ctx)) }
    val prefs = remember { RidePrefs.load(ctx) }

    // Re-read on every resume, not once. The grant happens in system settings,
    // so `remember` alone kept reporting "not exempt" until the process died.
    LifecycleResumeEffect(Unit) {
        exempt = isDozeExempt(ctx)
        onPauseOrDispose { }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (exempt) "Battery optimisation: exempt"
                else "Battery optimisation: not exempt",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Android may restrict background Bluetooth when the screen is off. " +
                    "A battery exemption can help continuity; it does not prove the cause of a connection drop.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            if (!exempt) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${ctx.packageName}"),
                            ),
                        )
                    }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }
                    }
                    // Re-read after the settings round-trip.
                    scope.launch {
                        delay(1500)
                        exempt = isDozeExempt(ctx)
                    }
                }) { Text("Open battery settings") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(
                    checked = prefs.resumeOnBoot,
                    onCheckedChange = { prefs.updateResumeOnBoot(it) },
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Reconnect automatically after a reboot",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RidesCard(rides: List<Ride>, totalRides: Int, totalDistanceKm: Double, connected: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$totalRides completed rides · ${String.format(Locale.US, "%.1f", totalDistanceKm)} km total",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Distance comes from the motorcycle odometer. Simulated phone GPS movement does not add ride distance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rides.isEmpty()) {
                Text(
                    "No rides yet. Connect to the scooter and ride.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                rides.take(5).forEach { ride ->
                    val df = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                    val whenText = df.format(Date(ride.startedAt))
                    val dist = String.format(Locale.US, "%.1f km", ride.distanceKm)
                    val dur = if (ride.inProgress) {
                        if (connected) "In progress · ${ride.durationMinutes()} min"
                        else "Open session · not connected"
                    } else {
                        "${ride.durationMinutes()} min"
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                whenText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                dur,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(dist, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** True when this package is on the battery-optimisation allowlist. */
private fun isDozeExempt(ctx: Context): Boolean {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java) ?: return false
    return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        .getOrDefault(false)
}

/**
 * Light / Dark / Auto.
 *
 * A segmented row rather than a switch: the middle option is "follow the
 * system", and a boolean cannot express three states without lying about one
 * of them.
 */
@Composable
private fun AppearanceCard() {
    val ctx = LocalContext.current
    val current = ThemePrefs.mode

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { ThemePrefs.set(ctx, mode) },
                    label = { Text(mode.label, textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


@Composable
private fun RaiderNavigationCard() {
    val ctx = LocalContext.current
    val prefs = remember { RidePrefs.load(ctx) }
    var single by remember { mutableStateOf(prefs.raiderPrimaryRowOnly) }
    var static by remember { mutableStateOf(prefs.raiderStaticText) }
    var rawEconomy by remember { mutableStateOf(prefs.showRaiderEconomyRaw) }
    var diagnostic by remember { mutableStateOf(dev.tvsopen.log.RaiderDiagnostics.enabled) }
    var exportStatus by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raider stationary NAV test", style = MaterialTheme.typography.titleMedium)
            Text("Physical display state is unknown until you mark it. Test while parked. " +
                "Change one switch at a time. Both are unverified experiments; turn both off for original rendering.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = single, onCheckedChange = {
                    single = it; prefs.raiderPrimaryRowOnly = it
                })
                Text("Primary row only (omit secondary)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = static, onCheckedChange = {
                    static = it; prefs.raiderStaticText = it
                })
                Text("Static text (no scrolling)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dev.tvsopen.log.RaiderDiagnostics.mark(false) }) { Text("NAV hidden") }
                OutlinedButton(onClick = { dev.tvsopen.log.RaiderDiagnostics.mark(true) }) { Text("NAV visible") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = rawEconomy, onCheckedChange = {
                    rawEconomy = it; prefs.showRaiderEconomyRaw = it
                })
                Text("Show average-economy candidate (units unverified)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSwitch(checked = diagnostic, onCheckedChange = {
                    diagnostic = it; dev.tvsopen.log.RaiderDiagnostics.enabled = it
                    if (!it) dev.tvsopen.log.RaiderDiagnostics.clear()
                })
                Text("Local telemetry and command trace")
            }
            Text("Capture keeps 10 seconds before and 20 seconds after a mark, sampled per ID. " +
                "Raw values are candidates, not calibrated measurements or ECU fault codes.")
            OutlinedButton(onClick = {
                exportStatus = runCatching {
                    val file = dev.tvsopen.log.RaiderDiagnostics.export(java.io.File(ctx.filesDir, "diagnostics"))
                    "Saved files/diagnostics/${file.name} locally"
                }.getOrElse { "Export failed" }
            }) { Text("Export marked capture") }
            if (exportStatus.isNotBlank()) Text(exportStatus)
        }
    }
}

@Composable
private fun ParkingCard() {
    val ctx = LocalContext.current
    val locator = remember { dev.tvsopen.session.ParkingLocator(ctx) }
    var status by remember { mutableStateOf("Request one foreground fix; no background permission needed.") }
    androidx.compose.runtime.DisposableEffect(locator) { onDispose { locator.cancel() } }
    val request = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) locator.request { status = it }
        else status = "Location permission declined; Bluetooth and navigation remain available"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parking location", style = MaterialTheme.typography.titleMedium)
            Text(status)
            Button(onClick = {
                status = "Waiting for a recent fix (up to 15 seconds)…"
                request.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Mark my parking") }
            OutlinedButton(onClick = {
                locator.cancel()
                RidePrefs.load(ctx).saveParked(0, null)
                dev.tvsopen.session.Session.clearParked()
                status = "Saved parking location removed"
            }) { Text("Forget parking location") }
        }
    }
}


@Composable
private fun VehicleEvidenceCard(selected: dev.tvsopen.protocol.VehicleModel) {
    var expanded by remember { mutableStateOf(false) }
    var allModels by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vehicle compatibility", style = MaterialTheme.typography.titleMedium)
            Text(selected.displayName)
            Text("Evidence labels explain what we know. They are not live connection indicators or a promise that every feature works.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide technical details" else "Show technical details")
            }
            if (expanded) {
                Text("● Reference code   ◆ Observed on vehicle   ◐ Inherited / provisional   ○ Needs confirmation",
                    style = MaterialTheme.typography.bodySmall)
                Nucleus.describe().filter { allModels || it.first == selected }.forEach { (model, facts) ->
                    HorizontalDivider()
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    facts.forEach { (fact, confidence) ->
                        val mark = when (confidence) {
                            Confidence.VERIFIED_FROM_APK -> "●"
                            Confidence.OBSERVED_ON_VEHICLE -> "◆"
                            Confidence.INHERITED_UNVERIFIED -> "◐"
                            Confidence.NEEDS_CAPTURE -> "○"
                        }
                        Text("$mark $fact", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { allModels = !allModels }) {
                    Text(if (allModels) "Show selected vehicle only" else "Show other vehicle profiles")
                }
                Text("${Pictograms.ids.size} reference pictogram IDs; these are not all verified on your motorcycle.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Navigation sends changed guidance promptly and refreshes control every 1.5 seconds when unchanged. " +
                    "Text scrolling is separate. An unavailable direction uses the straight placeholder with a phone warning.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
