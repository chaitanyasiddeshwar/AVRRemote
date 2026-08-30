package com.avrremote.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class AvrState(
    val connected: Boolean = false,
    val isWifiConnected: Boolean = true,
    val busy: Boolean = false,
    val busyMsg: String = "",
    val error: String? = null,
    val deviceIp: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val power: String? = null,
    val dynEq: Boolean? = null,
    val dynVol: String? = null,
    val surLev: String? = null,
    val refLev: String? = null,
    val multEq: String? = null,
    val preset: String? = null,
    val presetSupported: Boolean = true,
    val scanning: Boolean = false,
    val hasScanned: Boolean = false,
    val scanResults: List<DiscoveredDevice> = emptyList(),
    val probing: Boolean = false,
    val probeLog: List<String> = emptyList(),
    val channelSettings: ChannelSettingsSummary = ChannelSettingsSummary(),
    val channelSettingsLoading: Boolean = false,
    val channelSettingsError: String? = null,
)

object AvrController {

    val state = MutableStateFlow(AvrState())

    private val telnet = TelnetConnection()
    private val pool = Executors.newSingleThreadExecutor()
    private val probeStop = AtomicBoolean(false)
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val RE_ZM = Regex("^ZM\\s*(ON|OFF)", RegexOption.IGNORE_CASE)
    private val RE_DYNEQ = Regex("^PSDYNEQ\\s*(ON|OFF)", RegexOption.IGNORE_CASE)
    private val RE_DYNVOL = Regex("^PSDYNVOL\\s*(OFF|LIT|MED|HEV)", RegexOption.IGNORE_CASE)
    private val RE_SURLEV = Regex("^PSSURLEV\\s*(OFF|LIT|MED|HEV)", RegexOption.IGNORE_CASE)
    private val RE_REFLEV = Regex("^PSREFLEV\\s*(0|5|10|15)", RegexOption.IGNORE_CASE)
    private val RE_MULTEQ = Regex("^PSMULTEQ:\\s*(AUDYSSEY|FLAT|OFF)", RegexOption.IGNORE_CASE)
    private val RE_SPPR = Regex("^SPPR\\s*(1|2)", RegexOption.IGNORE_CASE)

    private val LEVEL_LABELS = mapOf("OFF" to "Off", "LIT" to "Light", "MED" to "Medium", "HEV" to "Heavy")

    fun init(context: Context) {
        appContext = context.applicationContext
        AvrRegistry.init(context)
        registerNetworkMonitor()
    }

    fun isWifiConnected(): Boolean {
        val ctx = appContext ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerNetworkMonitor() {
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val initialWifi = isWifiConnected()
        update { it.copy(isWifiConnected = initialWifi) }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val isWifi = isWifiConnected()
                update { it.copy(isWifiConnected = isWifi) }
            }

            override fun onLost(network: Network) {
                val isWifi = isWifiConnected()
                update { it.copy(isWifiConnected = isWifi) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                update { it.copy(isWifiConnected = isWifi) }
            }
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {}
    }

    fun onAppForeground() {
        val isWifi = isWifiConnected()
        update { it.copy(isWifiConnected = isWifi) }
        if (!isWifi) return

        submit {
            val rec = AvrRegistry.activeRecord() ?: AvrRegistry.avrs.firstOrNull()
            if (rec != null) {
                if (!telnet.isAlive()) {
                    tryConnect(rec.ip, rec.name, rec.model, rec.serial)
                } else {
                    refreshBlocking()
                }
            } else if (state.value.deviceIp.isNotEmpty() && !telnet.isAlive()) {
                tryConnect(state.value.deviceIp, state.value.deviceName, state.value.deviceModel, "")
            }
        }
    }

    fun onAppBackground() {
        stopProbe()
        submit {
            telnet.close()
        }
    }

    fun autoConnect() = submit {
        if (!isWifiConnected()) {
            update { it.copy(isWifiConnected = false) }
            return@submit
        }
        if (telnet.isAlive() && state.value.connected) return@submit

        val rec = AvrRegistry.activeRecord() ?: AvrRegistry.avrs.firstOrNull() ?: return@submit
        if (tryConnect(rec.ip, rec.name, rec.model, rec.serial)) return@submit

        update { it.copy(busy = true, busyMsg = "Searching for ${rec.name}...") }
        val found = try {
            SsdpDiscoverer.discover(6000)
        } catch (_: Exception) {
            emptyList()
        }
        val match = found.firstOrNull { it.serial.isNotEmpty() && it.serial == rec.serial }
        if (match != null) {
            tryConnect(match.ip, rec.name, rec.model, rec.serial)
        } else {
            update {
                it.copy(
                    busy = false,
                    busyMsg = "",
                    error = "Could not connect to ${rec.name} at ${rec.ip}. Is the receiver powered on?",
                )
            }
        }
    }

    fun retryConnect() = submit {
        if (!isWifiConnected()) {
            update { it.copy(isWifiConnected = false, error = "Please connect to Wi-Fi first") }
            return@submit
        }
        val rec = AvrRegistry.activeRecord() ?: AvrRegistry.avrs.firstOrNull()
        if (rec != null) {
            if (tryConnect(rec.ip, rec.name, rec.model, rec.serial)) return@submit
            update { it.copy(busy = true, busyMsg = "Searching for ${rec.name}...") }
            val found = try {
                SsdpDiscoverer.discover(6000)
            } catch (_: Exception) {
                emptyList()
            }
            val match = found.firstOrNull { it.serial.isNotEmpty() && it.serial == rec.serial }
            if (match != null) {
                tryConnect(match.ip, rec.name, rec.model, rec.serial)
            } else {
                update {
                    it.copy(
                        busy = false,
                        busyMsg = "",
                        error = "Could not connect to ${rec.name} at ${rec.ip}. Is the receiver powered on and on this network?",
                    )
                }
            }
        } else if (state.value.deviceIp.isNotEmpty()) {
            tryConnect(state.value.deviceIp, state.value.deviceName, state.value.deviceModel, "")
        } else {
            scan()
        }
    }

    fun connect(ip: String, name: String, model: String, serial: String) = submit {
        if (!isWifiConnected()) {
            update { it.copy(isWifiConnected = false, error = "Please connect to Wi-Fi first") }
            return@submit
        }
        tryConnect(ip, name, model, serial)
    }

    fun disconnect() = submit {
        telnet.close()
        update { AvrState(isWifiConnected = isWifiConnected()) }
    }

    fun scan() = submit {
        if (!isWifiConnected()) {
            update { it.copy(isWifiConnected = false, error = "Please connect to Wi-Fi to scan for receivers") }
            return@submit
        }
        update { it.copy(scanning = true, error = null) }
        var lock: WifiManager.MulticastLock? = null
        val devices = try {
            val wifi = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("ssdp")
            lock?.setReferenceCounted(false)
            lock?.acquire()
            SsdpDiscoverer.discover(8000)
        } catch (e: Exception) {
            update { it.copy(error = "Scan failed: ${e.message}") }
            emptyList()
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
        update { it.copy(scanning = false, hasScanned = true, scanResults = devices) }
    }

    fun refresh() = submit { refreshBlocking() }

    fun fetchChannelSettings() = submit { fetchChannelSettingsBlocking() }

    private fun ensureConnected(): Boolean {
        if (telnet.isAlive()) return true
        val ip = state.value.deviceIp.ifEmpty { AvrRegistry.activeRecord()?.ip ?: "" }
        if (ip.isEmpty()) return false
        return try {
            telnet.connect(ip)
            val power = telnet.exec("ZM?", RE_ZM, 2000)
            if (power != null) {
                val rec = AvrRegistry.activeRecord()
                update {
                    it.copy(
                        connected = true,
                        deviceIp = ip,
                        deviceName = it.deviceName.ifEmpty { rec?.name ?: "AV Receiver" },
                        deviceModel = it.deviceModel.ifEmpty { rec?.model ?: "" },
                        power = matchValue(power, RE_ZM),
                        error = null,
                    )
                }
                true
            } else {
                telnet.close()
                false
            }
        } catch (_: Exception) {
            telnet.close()
            false
        }
    }

    private fun sendCommand(cmd: String, expect: Regex? = null, timeoutMs: Long = 3000): String? {
        if (!ensureConnected()) {
            update { it.copy(error = "Connection lost. Reconnecting...") }
            return null
        }
        var line = telnet.exec(cmd, expect, timeoutMs)
        if (line == null && !telnet.isAlive()) {
            // Socket was closed or broken, retry connection once
            if (ensureConnected()) {
                line = telnet.exec(cmd, expect, timeoutMs)
            }
        }
        if (line == null && !telnet.isAlive()) {
            update { it.copy(error = "Connection lost to receiver") }
        }
        return line
    }

    fun setDynEq(on: Boolean) = submit {
        val line = sendCommand(if (on) "PSDYNEQ ON" else "PSDYNEQ OFF", RE_DYNEQ, 4000)
        val v = matchValue(line, RE_DYNEQ)
        if (v != null) {
            update { it.copy(dynEq = v == "ON", error = null) }
        } else if (telnet.isAlive()) {
            update { it.copy(dynEq = on) }
        }
    }

    fun setDynVol(value: String) = submit {
        var line = sendCommand("PSDYNVOL $value", RE_DYNVOL, 2500)
        var v = matchValue(line, RE_DYNVOL)
        if (v == null) {
            line = sendCommand("PSDYNVOL ?", RE_DYNVOL)
            v = matchValue(line, RE_DYNVOL)
        }
        val ok = v == value
        val label = LEVEL_LABELS[value] ?: value
        update {
            it.copy(
                dynVol = v ?: it.dynVol,
                error = if (ok) null else if (!telnet.isAlive()) "Connection lost" else "Receiver rejected Dynamic Volume '$label'",
            )
        }
    }

    fun setRefLev(value: String) = submit {
        if (state.value.dynEq != true) {
            update { it.copy(error = "Enable Dynamic EQ first") }
            return@submit
        }
        val line = sendCommand("PSREFLEV $value", RE_REFLEV, 2500)
        val v = matchValue(line, RE_REFLEV) ?: value
        update { it.copy(refLev = v) }
    }

    fun startProbe() = submit {
        probeStop.set(false)
        update { it.copy(probing = true, probeLog = emptyList()) }
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 60_000
        while (System.currentTimeMillis() < deadline && !probeStop.get()) {
            val line = telnet.readLine(500)
            if (line == null) {
                update {
                    it.copy(probeLog = it.probeLog + "--- connection lost ---", probing = false)
                }
                return@submit
            }
            if (line.isEmpty()) continue
            val ts = "+%.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
            update { st -> st.copy(probeLog = (st.probeLog + "$ts  $line").takeLast(300)) }
        }
        update { it.copy(probing = false) }
    }

    fun stopProbe() {
        probeStop.set(true)
    }

    fun clearProbeLog() = submit {
        update { it.copy(probeLog = emptyList()) }
    }

    fun setSurLev(value: String) = submit {
        if (state.value.dynEq != true) {
            update { it.copy(error = "Enable Dynamic EQ first") }
            return@submit
        }
        var line = sendCommand("PSSURLEV $value", RE_SURLEV, 2500)
        var v = matchValue(line, RE_SURLEV)
        if (v == null) {
            line = sendCommand("PSSURLEV ?", RE_SURLEV)
            v = matchValue(line, RE_SURLEV)
        }
        val ok = v == value
        val label = LEVEL_LABELS[value] ?: value
        update {
            it.copy(
                surLev = v ?: it.surLev,
                error = if (ok) null else if (!telnet.isAlive()) "Connection lost" else "Receiver rejected Sound level compensation '$label'",
            )
        }
    }

    fun setMultEq(curve: String) = submit {
        val line = sendCommand("PSMULTEQ:$curve", RE_MULTEQ, 4000)
        val v = matchValue(line, RE_MULTEQ) ?: curve
        update { it.copy(multEq = v) }
    }

    fun setPreset(n: String) = submit {
        update { it.copy(busy = true, busyMsg = "Checking power...") }
        val powerLine = sendCommand("ZM?", RE_ZM)
        val power = matchValue(powerLine, RE_ZM)
        if (power == "OFF") {
            sendCommand("ZMON", RE_ZM, 4000)
            update { it.copy(busyMsg = "Powering on receiver (~10 s)...") }
            Thread.sleep(10000)
        }
        val line = sendCommand("SPPR $n", Regex("^SPPR\\s*$n", RegexOption.IGNORE_CASE), 4000)
        val v = matchValue(line, RE_SPPR) ?: n
        update {
            it.copy(
                preset = v,
                power = "ON",
                busy = false,
                busyMsg = "",
            )
        }
    }

    private fun tryConnect(ip: String, name: String, model: String, serial: String): Boolean {
        update { it.copy(busy = true, busyMsg = "Connecting to $ip...", error = null) }
        return try {
            telnet.connect(ip)
            val resp = telnet.exec("ZM?", RE_ZM) ?: throw Exception("no response from receiver")
            if (serial.isNotEmpty()) {
                AvrRegistry.upsert(AvrRecord(serial, ip, name, model))
            }
            update {
                it.copy(
                    connected = true,
                    deviceIp = ip,
                    deviceName = name.ifEmpty { "AV Receiver" },
                    deviceModel = model,
                    power = matchValue(resp, RE_ZM),
                    busy = false,
                    busyMsg = "",
                    error = null,
                )
            }
            refreshBlocking()
            fetchChannelSettingsBlocking()
            true
        } catch (e: Exception) {
            telnet.close()
            update {
                it.copy(
                    connected = false,
                    busy = false,
                    busyMsg = "",
                    error = "Connection failed to $ip: ${e.message}",
                )
            }
            false
        }
    }

    private fun refreshBlocking() {
        if (!ensureConnected()) {
            update { it.copy(error = "Connection lost") }
            return
        }
        val power = matchValue(sendCommand("ZM?", RE_ZM), RE_ZM)
        if (power == null) {
            update { it.copy(error = "No response from receiver") }
            return
        }
        val dynEq = matchValue(sendCommand("PSDYNEQ ?", RE_DYNEQ), RE_DYNEQ)
        val dynVol = matchValue(sendCommand("PSDYNVOL ?", RE_DYNVOL), RE_DYNVOL)
        val surLev = matchValue(sendCommand("PSSURLEV ?", RE_SURLEV), RE_SURLEV)
        val refLev = matchValue(sendCommand("PSREFLEV ?", RE_REFLEV), RE_REFLEV)
        val multEq = matchValue(sendCommand("PSMULTEQ: ?", RE_MULTEQ), RE_MULTEQ)
        val preset = matchValue(sendCommand("SPPR ?", RE_SPPR, 4000), RE_SPPR)
        update {
            it.copy(
                power = power,
                dynEq = dynEq?.let { v -> v == "ON" },
                dynVol = dynVol,
                surLev = surLev,
                refLev = refLev,
                multEq = multEq,
                preset = preset,
                presetSupported = preset != null,
                error = null,
            )
        }
    }

    private fun fetchChannelSettingsBlocking() {
        if (!ensureConnected()) {
            update { it.copy(channelSettingsError = "Not connected to receiver", channelSettingsLoading = false) }
            return
        }
        update { it.copy(channelSettingsLoading = true, channelSettingsError = null) }
        try {
            val ip = state.value.deviceIp.ifEmpty { AvrRegistry.activeRecord()?.ip ?: "" }

            // 1. Audyssey Binary port 1256 (GET_AVRSTS ChSetup)
            val port1256Active = if (ip.isNotEmpty()) {
                try { AvrBinaryProtocol.fetchActiveChannels(ip) } catch (_: Exception) { null }
            } else null

            // 2. Telnet queries
            val sspcLines = telnet.collectLines("SSSPC ?", 1200, 300)
            val trimLines = telnet.collectLines("CV ?", 1200, 300, Regex("CVEND", RegexOption.IGNORE_CASE))
            val distanceLines = telnet.collectLines("SSSDE ?", 1500, 350)
            val crossoverLines = telnet.collectLines("SSCFR ?", 1500, 350)
            val levelLines = telnet.collectLines("SSLEV ?", 1500, 350, Regex("SSLEV\\s*END", RegexOption.IGNORE_CASE))
            val lpfLine = telnet.exec("SSLFL ?", Regex("^SSLFL", RegexOption.IGNORE_CASE), 1200)
            val subModeLine = telnet.exec("SSSWO ?", Regex("^SSSWO", RegexOption.IGNORE_CASE), 1200)

            val summary = ChannelSettingsParser.parseChannelSettings(
                distanceLines = distanceLines,
                crossoverLines = crossoverLines,
                levelLines = levelLines,
                trimLines = trimLines,
                sspcLines = sspcLines,
                lpfLfeLine = lpfLine,
                subModeLine = subModeLine,
                port1256ActiveChannels = port1256Active,
            )

            update {
                it.copy(
                    channelSettings = summary,
                    channelSettingsLoading = false,
                    channelSettingsError = if (summary.channels.isEmpty()) "No channel settings reported by receiver" else null,
                )
            }
        } catch (e: Exception) {
            update {
                it.copy(
                    channelSettingsLoading = false,
                    channelSettingsError = "Failed to load channel settings: ${e.message}",
                )
            }
        }
    }

    private fun matchValue(line: String?, re: Regex): String? =
        line?.let { re.find(it)?.groupValues?.getOrNull(1)?.uppercase() }

    private fun update(f: (AvrState) -> AvrState) {
        state.update(f)
    }

    private fun submit(block: () -> Unit) {
        pool.submit {
            try {
                block()
            } catch (e: Exception) {
                update { it.copy(busy = false, busyMsg = "", error = e.message ?: "Error") }
            }
        }
    }
}
