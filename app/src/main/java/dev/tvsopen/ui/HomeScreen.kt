package dev.tvsopen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dev.tvsopen.ui.theme.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import dev.tvsopen.session.ConnectionState1
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import dev.tvsopen.session.Session
import dev.tvsopen.session.ParkedInfo
import dev.tvsopen.session.Telemetry
import dev.tvsopen.transport.ConnectionState
import dev.tvsopen.transport.label
import dev.tvsopen.ui.theme.LocalTilePalette
import dev.tvsopen.ui.theme.NumberStyle
import kotlinx.coroutines.delay

/** The rider dashboard. Unavailable readings are displayed as an em dash. */
@Composable
fun HomeScreen(
    connection: ConnectionState1,
    parked: ParkedInfo?,
    findMyEnabled: Boolean,
    navMirroring: Boolean,
    rideMode: Boolean,
    onFindMyChange: (Boolean) -> Unit,
    onNavMirrorChange: (Boolean) -> Unit,
    onRideModeChange: (Boolean) -> Unit,
    onOpenOverview: () -> Unit,
    onOpenParked: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connection.state == ConnectionState.CONNECTED

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(connection, connected)
        Spacer(Modifier.height(12.dp))
        NavigationStrip()
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            RideSpeed(connected)
            Spacer(Modifier.height(16.dp))
            ConnectCard(connection.state, onConnect)
            Spacer(Modifier.height(24.dp))
            SectionLabel("Your vehicle")

            // Card navigation rather than a value grid, mirroring the official app's
            // dashboard. The values live on the Overview tab where they belong.
            val ctx = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NavCard(Modifier.weight(1f), Icons.Filled.Speed, "Overview", onOpenOverview)
                NavCard(Modifier.weight(1f), Icons.Filled.Place, "Last parked") {
                    if (!openParkedLocation(ctx, parked)) onOpenParked()
                }
            }
            Spacer(Modifier.height(12.dp))
            // This preference is shared with More and read by BootReceiver.
            RideModeCard(rideMode, onRideModeChange)

            Spacer(Modifier.height(20.dp))
            SectionLabel("While riding")

            MirrorNavCard(navMirroring, onNavMirrorChange)
            Spacer(Modifier.height(12.dp))
            if (connection.model == dev.tvsopen.protocol.VehicleModel.RAIDER_125 ||
                connection.model == dev.tvsopen.protocol.VehicleModel.RAIDER_180) {
                Text("Find parked motorcycle uses the saved phone location. " +
                    "Remote horn and light activation is not verified for Raider and is disabled.",
                    style = MaterialTheme.typography.bodySmall)
            } else FindMyCard(findMyEnabled, onFindMyChange)
            Spacer(Modifier.height(12.dp))
            LastParkedCard(parked) {
                openParkedLocation(ctx, parked)
            }

            // Telemetry freshness is the number that actually tells you whether the
            // link is alive. The frame counter belongs in the Log tab, not here.
            Spacer(Modifier.height(20.dp))
            SectionLabel("Link")
            LinkCard()

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun NavCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(104.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConnectCard(state: ConnectionState, onClick: () -> Unit) {
    val pending = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING
    val connected = state == ConnectionState.CONNECTED
    Surface(
        onClick = onClick,
        enabled = !pending,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = LocalTilePalette.current.accent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (pending) state.label else if (connected) "Find another vehicle" else "Connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (pending) "Waiting for the vehicle" else "Nearby Bluetooth vehicles",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
/**
 * Eyebrow section label, per `DESIGN.md` (`badge-pill` / `caption-strong`:
 * small uppercase muted label). Text-only: on a dense telemetry screen a
 * filled pill behind every heading would be noise.
 */
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun Header(connection: ConnectionState1, connected: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Live", style = MaterialTheme.typography.headlineLarge)
            Text(
                connection.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Pill(
            if (connected) "Connected" else connection.state.label,
            connected,
        )

    }
}

@Composable
private fun Pill(text: String, on: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) LocalTilePalette.current.accent
        else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}



/**
 * The headline feature, so it sits above the rest of the vehicle cards and
 * reports where instructions are coming from — a user needs to know which maps
 * app the cluster is currently being driven by, and that it is their own app,
 * not a TVS server.
 */
@Composable
private fun MirrorNavCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val hud by Session.hud.collectAsStateWithLifecycle()
    ActionCard(
        background = LocalTilePalette.current.accent,
        icon = Icons.Filled.Navigation,
        title = "Mirror navigation",
        subtitle = when {
            !enabled -> "Off — turn-by-turn is not being sent"
            hud.active -> "Live from ${hud.provider ?: "your maps app"}"
            else -> "On — waiting for a navigation step"
        },
        trailing = { AppSwitch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
internal fun LastParkedCard(parked: ParkedInfo?, onClick: () -> Unit = {}) {
    val tick = rememberTicker()
    val ago = remember(parked, tick) {
        parked?.let { relativeTime(System.currentTimeMillis() - it.atMillis) }
    }
    val clickable = parked?.label?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.size == 2
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.Filled.Place,
        title = "Last Parked",
        subtitle = parked?.label ?: "Not recorded on this device",
        onClick = if (clickable) onClick else null,
        trailing = {
            Text(
                if (ago != null) "$ago ago" else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only accent the distance when there is one. A blue em dash reads
            // as a stray fragment rather than as "no data".
            if (parked?.distanceMeters != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "${parked.distanceMeters}m away",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

internal fun openParkedLocation(context: android.content.Context, parked: ParkedInfo?): Boolean {
    val label = parked?.label ?: return false
    val parts = label.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size != 2) return false
    val lat = parts[0]
    val lon = parts[1]
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Parked scooter)")
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}


@Composable
private fun FindMyCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.accent,
        icon = Icons.Filled.NotificationsActive,
        title = "Find My Scooter",
        subtitle = "Flash lights & sound horn",
        trailing = { AppSwitch(checked = enabled, onCheckedChange = onChange) },
    )
}

@Composable
private fun ActionCard(
    background: Color,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            trailing()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            content = content,
        )
    }
}

/**
 * A 1 Hz tick so age labels keep advancing while no flow emits. A `remember`
 * keyed only on a timestamp froze "Telemetry live" on screen forever after a
 * link died, because no more frames arrived to change the key.
 */
@Composable
private fun rememberTicker(): Long {
    val tick = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick.value++
        }
    }
    return tick.value
}

/**
 * Telemetry freshness.
 *
 * The single most useful "is this thing actually working" signal. Dashboards
 * fill with values from the last frame received, so without this the screen
 * looks identical whether the link is streaming or died a minute ago.
 */
@Composable
private fun LinkCard() {
    val telemetry by Session.telemetry.collectAsStateWithLifecycle()
    val tick = rememberTicker()
    val age = remember(telemetry.lastFrameAt, tick) {
        if (telemetry.lastFrameAt == 0L) null
        else System.currentTimeMillis() - telemetry.lastFrameAt
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                when {
                    age == null -> "No telemetry received yet"
                    age < 3_000 -> "Telemetry live"
                    age < Telemetry.STALE_AFTER_MS -> "Telemetry ${age / 1000}s old"
                    else -> "Telemetry stale — ${age / 1000}s since last frame"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RideSpeed(connected: Boolean) {
    val telemetry by Session.telemetry.collectAsStateWithLifecycle()
    val tick = rememberTicker()
    val speed = remember(telemetry, tick, connected) {
        if (connected) telemetry.forDisplay(System.currentTimeMillis()).speedKmh else null
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Current speed", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(speed?.toString() ?: "—", style = NumberStyle.copy(fontSize = 56.sp, lineHeight = 64.sp))
                Text("km/h", Modifier.padding(start = 8.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun NavigationStrip() {
    val hud by Session.hud.collectAsStateWithLifecycle()
    val arrow = if (hud.active) dev.tvsopen.nav.NavShortCodes.uiArrow(hud.pictogramId) else ""
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                if (arrow.isEmpty()) {
                    Icon(Icons.Filled.Navigation, contentDescription = null)
                } else {
                    Text(arrow, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 48.sp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    if (hud.active && hud.distanceMeters >= 0) "${hud.distanceMeters} m" else "—",
                    style = NumberStyle.copy(fontSize = 36.sp, lineHeight = 44.sp),
                    maxLines = 1,
                )
                Text(
                    if (hud.active) hud.street?.takeIf { it.isNotBlank() }
                        ?: hud.instructionTitle?.takeIf { it.isNotBlank() } ?: "Next maneuver"
                    else "Waiting for directions",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return when {
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        else -> "${min / (60 * 24)}d"
    }
}

@Composable
private fun RideModeCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ActionCard(
        background = LocalTilePalette.current.neutral,
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        title = "Reconnect after reboot",
        subtitle = if (enabled) "Reconnect to the saved Raider after the phone restarts"
        else "Off — enable to reconnect after a phone restart",
        trailing = {
            AppSwitch(checked = enabled, onCheckedChange = onChange)
        },
    )
}
