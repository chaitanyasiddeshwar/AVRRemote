package com.avrremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AvrController.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val st by AvrController.state.collectAsState()
                    if (st.connected) ControlScreen(st) else DeviceScreen(st)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AvrController.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        AvrController.onAppBackground()
    }
}

@Composable
fun WifiWarningBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Wi-Fi Disconnected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Please connect your phone to your home Wi-Fi network to find and control your AV receiver.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
fun DeviceScreen(st: AvrState) {
    LaunchedEffect(Unit) {
        AvrController.autoConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("AVR Remote", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Control your Denon/Marantz receiver",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (!st.isWifiConnected) {
            WifiWarningBanner()
        }

        st.error?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = { AvrController.retryConnect() },
                            enabled = !st.busy && !st.scanning && st.isWifiConnected,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Retry")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { AvrController.scan() },
                            enabled = !st.busy && !st.scanning && st.isWifiConnected,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Scan network")
                        }
                    }
                }
            }
        }

        val saved = AvrRegistry.avrs
        if (saved.isNotEmpty()) {
            Text(
                "Saved receivers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            saved.forEach { rec ->
                DeviceRow(rec.name, rec.model, rec.ip) {
                    AvrController.connect(rec.ip, rec.name, rec.model, rec.serial)
                }
            }
        }

        if (st.error == null) {
            Button(
                onClick = { AvrController.scan() },
                enabled = !st.scanning && !st.busy && st.isWifiConnected,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
            ) {
                if (st.scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (st.scanning) "Scanning..." else "Scan network")
            }
        }

        if (st.scanResults.isNotEmpty()) {
            Text(
                "Discovered devices",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            st.scanResults.forEach { d ->
                val note = if (d.isAvrLike()) d.ip else "${d.ip} (may not be an AVR)"
                DeviceRow(d.name, d.model, note) {
                    AvrController.connect(d.ip, d.name, d.model, d.serial)
                }
            }
        } else if (st.hasScanned && !st.scanning) {
            Text(
                "No devices found. Is the receiver powered on, on this network, with Network Control enabled?",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (st.busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.busyMsg, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ControlScreen(st: AvrState) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (!st.isWifiConnected) {
            WifiWarningBanner()
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.deviceName, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(st.deviceModel, st.deviceIp).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { AvrController.disconnect() }) { Text("Disconnect") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Tab Navigation
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        "Controls",
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    if (st.channelSettings.channels.isEmpty() && !st.channelSettingsLoading) {
                        AvrController.fetchChannelSettings()
                    }
                },
                text = {
                    Text(
                        "Channel Settings",
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }

        when (selectedTab) {
            0 -> AudioControlsContent(st)
            1 -> ChannelSettingsContent(st)
        }
    }
}

@Composable
fun AudioControlsContent(st: AvrState) {
    st.error?.let { err ->
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { AvrController.refresh() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Retry")
                }
            }
        }
    }

    SwitchRow("Dynamic EQ", st.dynEq) { AvrController.setDynEq(it) }

    Text(
        "Sound level compensation",
        style = MaterialTheme.typography.titleSmall,
        color = if (st.dynEq == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    ChipRow(
        options = listOf("OFF" to "Off", "LIT" to "Light", "MED" to "Medium", "HEV" to "Heavy"),
        selected = st.surLev,
        enabled = st.dynEq == true,
        onPick = { AvrController.setSurLev(it) },
    )

    Text(
        "DynEQ reference offset",
        style = MaterialTheme.typography.titleSmall,
        color = if (st.dynEq == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    ChipRow(
        options = listOf("0" to "0 dB", "5" to "5 dB", "10" to "10 dB", "15" to "15 dB"),
        selected = st.refLev,
        enabled = st.dynEq == true,
        onPick = { AvrController.setRefLev(it) },
    )
    Text(
        "Higher offset = milder loudness compensation",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        "Dynamic Volume",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp),
    )
    ChipRow(
        options = listOf("OFF" to "Off", "LIT" to "Light", "MED" to "Medium", "HEV" to "Heavy"),
        selected = st.dynVol,
        onPick = { AvrController.setDynVol(it) },
    )

    Text(
        "Audyssey MultEQ",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp),
    )
    ChipRow(
        options = listOf("AUDYSSEY" to "Audyssey", "FLAT" to "Flat", "OFF" to "Off"),
        selected = st.multEq,
        onPick = { AvrController.setMultEq(it) },
    )

    Text(
        "Speaker Preset",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp),
    )
    if (st.presetSupported) {
        ChipRow(
            options = listOf("1" to "Preset 1", "2" to "Preset 2"),
            selected = st.preset,
            onPick = { AvrController.setPreset(it) },
        )
    } else {
        Text(
            "Not supported by this receiver",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (st.busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(st.busyMsg, style = MaterialTheme.typography.bodySmall)
        }
    }

    TextButton(
        onClick = { AvrController.refresh() },
        modifier = Modifier.padding(top = 20.dp),
    ) {
        Text("Refresh status")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    ProbeSection(st)
}

@Composable
fun ChannelSettingsContent(st: AvrState) {
    LaunchedEffect(Unit) {
        if (st.channelSettings.channels.isEmpty() && !st.channelSettingsLoading) {
            AvrController.fetchChannelSettings()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Summary & Actions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Channel Configuration",
                    style = MaterialTheme.typography.titleMedium,
                )
                val updateTime = if (st.channelSettings.lastUpdatedMs > 0) {
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    "Updated " + sdf.format(Date(st.channelSettings.lastUpdatedMs))
                } else {
                    "Read-only from AVR"
                }
                Text(
                    updateTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { AvrController.fetchChannelSettings() },
                enabled = !st.channelSettingsLoading && !st.busy,
            ) {
                if (st.channelSettingsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (st.channelSettingsLoading) "Reading..." else "Refresh")
            }
        }

        // Info Badges (LPF for LFE, Subwoofer Mode, Distance Unit, Preset)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            st.channelSettings.lpfLfe?.let { lpf ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        "LPF: $lpf",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            st.channelSettings.subMode?.let { mode ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        "Sub: $mode",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Text(
                    "Unit: ${if (st.channelSettings.distanceUnit == "ft") "Feet" else "Meters"}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            st.preset?.let { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        "Preset $p",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        st.channelSettingsError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { AvrController.fetchChannelSettings() }) {
                        Text("Retry")
                    }
                }
            }
        }

        if (st.channelSettings.channels.isEmpty()) {
            if (st.channelSettingsLoading) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Collecting channel levels, distances & crossovers...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (st.channelSettingsError == null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No channel settings loaded yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { AvrController.fetchChannelSettings() }) {
                            Text("Query Receiver")
                        }
                    }
                }
            }
        } else {
            ChannelSettingsTable(
                channels = st.channelSettings.channels,
                unit = st.channelSettings.distanceUnit,
            )
        }
    }
}

@Composable
fun ChannelSettingsTable(
    channels: List<ChannelSetting>,
    unit: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Channel",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.3f),
                )
                Text(
                    "Level",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.9f),
                )
                Text(
                    "Distance",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.0f),
                )
                Text(
                    "Crossover",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.1f),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            channels.forEachIndexed { index, ch ->
                val isEven = index % 2 == 0
                val rowBg = if (isEven) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Column 1: Channel
                    Column(modifier = Modifier.weight(1.3f)) {
                        Text(
                            ch.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            ch.id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    // Column 2: Level
                    Column(
                        modifier = Modifier.weight(0.9f),
                        horizontalAlignment = Alignment.End,
                    ) {
                        val levelText = ChannelSettingsParser.formatLevel(ch.levelDb)
                        Text(
                            levelText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End,
                            color = when {
                                ch.levelDb != null && ch.levelDb > 0.001f -> MaterialTheme.colorScheme.primary
                                ch.levelDb != null && ch.levelDb < -0.001f -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (ch.trimDb != null && ch.trimDb != 0.0f && ch.trimDb != ch.levelDb) {
                            Text(
                                "Trim ${ChannelSettingsParser.formatLevel(ch.trimDb)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }

                    // Column 3: Distance
                    Column(
                        modifier = Modifier.weight(1.0f),
                        horizontalAlignment = Alignment.End,
                    ) {
                        if (ch.distanceVal != null) {
                            val distPrimary = if (unit.equals("ft", ignoreCase = true)) {
                                "%.1f ft".format(ch.distanceVal)
                            } else {
                                "%.2f m".format(ch.distanceVal)
                            }
                            val distSecondary = if (unit.equals("ft", ignoreCase = true)) {
                                "%.2f m".format(ch.distanceVal * 0.3048f)
                            } else {
                                "%.1f ft".format(ch.distanceVal / 0.3048f)
                            }
                            Text(
                                distPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.End,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                distSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        } else {
                            Text(
                                "--",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                            )
                        }
                    }

                    // Column 4: Crossover
                    Column(
                        modifier = Modifier.weight(1.1f),
                        horizontalAlignment = Alignment.End,
                    ) {
                        val xover = ch.crossover ?: "--"
                        Text(
                            xover,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (xover == "Full Band") FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.End,
                            color = when {
                                xover == "Full Band" -> MaterialTheme.colorScheme.secondary
                                xover.startsWith("LPF") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                if (index < channels.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
fun ProbeSection(st: AvrState) {
    Text("Remote probe", style = MaterialTheme.typography.titleMedium)
    Text(
        "Press buttons on the AVR remote - every change the receiver reports appears below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        if (st.probing) {
            Button(onClick = { AvrController.stopProbe() }) { Text("Stop") }
        } else {
            Button(onClick = { AvrController.startProbe() }) { Text("Probe for 60 s") }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { AvrController.clearProbeLog() },
            enabled = !st.probing && st.probeLog.isNotEmpty(),
        ) {
            Text("Clear")
        }
    }
    val logScroll = rememberScrollState()
    LaunchedEffect(st.probeLog.size) {
        if (st.probeLog.isNotEmpty()) logScroll.scrollTo(logScroll.maxValue)
    }
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .verticalScroll(logScroll),
    ) {
        if (st.probeLog.isEmpty()) {
            Text(
                if (st.probing) "Listening..." else "No events captured yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            st.probeLog.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun DeviceRow(name: String, model: String, sub: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(name.ifEmpty { "Unknown device" }, style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(model, sub).filter { it.isNotEmpty() }.joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean?, onSet: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Switch(
            checked = value == true,
            onCheckedChange = onSet,
            enabled = value != null,
        )
    }
}

@Composable
fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean = true,
    onPick: (String) -> Unit,
) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = { Text(label) },
                enabled = enabled,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
