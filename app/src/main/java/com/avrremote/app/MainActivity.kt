package com.avrremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
}

@Composable
fun DeviceScreen(st: AvrState) {
    LaunchedEffect(Unit) { AvrController.autoConnect() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("AVR Remote", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Control your Denon/Marantz receiver",
            style = MaterialTheme.typography.bodySmall,
        )

        st.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        val saved = AvrRegistry.avrs
        if (saved.isNotEmpty()) {
            Text(
                "Saved receivers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            saved.forEach { rec ->
                DeviceRow(rec.name, rec.model, rec.ip) {
                    AvrController.connect(rec.ip, rec.name, rec.model, rec.serial)
                }
            }
        }

        Button(
            onClick = { AvrController.scan() },
            enabled = !st.scanning,
            modifier = Modifier
                .padding(top = 20.dp)
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
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

        st.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        SwitchRow("Dynamic EQ", st.dynEq) { AvrController.setDynEq(it) }
        SwitchRow("Dynamic Volume", st.dynVol) { AvrController.setDynVol(it) }

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
    }
}

@Composable
fun DeviceRow(name: String, model: String, sub: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
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
fun ChipRow(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onPick(value) },
                label = { Text(label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
