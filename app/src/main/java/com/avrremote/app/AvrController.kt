package com.avrremote.app

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class AvrState(
    val connected: Boolean = false,
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
)

object AvrController {

    val state = MutableStateFlow(AvrState())

    private val telnet = TelnetConnection()
    private val pool = Executors.newSingleThreadExecutor()
    private val probeStop = AtomicBoolean(false)
    private var appContext: Context? = null

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
    }

    fun autoConnect() = submit {
        val rec = AvrRegistry.activeRecord() ?: return@submit
        if (tryConnect(rec.ip, rec.name, rec.model, rec.serial)) return@submit
        update { it.copy(busy = true, busyMsg = "Searching for ${rec.name}...") }
        val found = try {
            SsdpDiscoverer.discover(6000)
        } catch (_: Exception) {
            emptyList()
        }
        val match = found.firstOrNull { it.serial.isNotEmpty() && it.serial == rec.serial }
        if (match != null) tryConnect(match.ip, rec.name, rec.model, rec.serial)
        update { it.copy(busy = false, busyMsg = "") }
    }

    fun connect(ip: String, name: String, model: String, serial: String) = submit {
        tryConnect(ip, name, model, serial)
    }

    fun disconnect() = submit {
        telnet.close()
        update { AvrState() }
    }

    fun scan() = submit {
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

    fun setDynEq(on: Boolean) = submit {
        val line = telnet.exec(if (on) "PSDYNEQ ON" else "PSDYNEQ OFF", RE_DYNEQ, 4000)
        val v = matchValue(line, RE_DYNEQ)
        update { it.copy(dynEq = if (v != null) v == "ON" else on) }
    }

    fun setDynVol(value: String) = submit {
        var v = matchValue(telnet.exec("PSDYNVOL $value", RE_DYNVOL, 2500), RE_DYNVOL)
        if (v == null) v = matchValue(telnet.exec("PSDYNVOL ?", RE_DYNVOL), RE_DYNVOL)
        val ok = v == value
        val label = LEVEL_LABELS[value] ?: value
        update {
            it.copy(
                dynVol = v ?: it.dynVol,
                error = if (ok) null else "Receiver rejected Dynamic Volume '$label'",
            )
        }
    }

    fun setRefLev(value: String) = submit {
        if (state.value.dynEq != true) {
            update { it.copy(error = "Enable Dynamic EQ first") }
            return@submit
        }
        val line = telnet.exec("PSREFLEV $value", RE_REFLEV, 2500)
        update { it.copy(refLev = matchValue(line, RE_REFLEV) ?: value) }
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
        var v = matchValue(telnet.exec("PSSURLEV $value", RE_SURLEV, 2500), RE_SURLEV)
        if (v == null) v = matchValue(telnet.exec("PSSURLEV ?", RE_SURLEV), RE_SURLEV)
        val ok = v == value
        val label = LEVEL_LABELS[value] ?: value
        update {
            it.copy(
                surLev = v ?: it.surLev,
                error = if (ok) null else "Receiver rejected Sound level compensation '$label'",
            )
        }
    }

    fun setMultEq(curve: String) = submit {
        val line = telnet.exec("PSMULTEQ:$curve", RE_MULTEQ, 4000)
        update { it.copy(multEq = matchValue(line, RE_MULTEQ) ?: curve) }
    }

    fun setPreset(n: String) = submit {
        update { it.copy(busy = true, busyMsg = "Checking power...") }
        val power = matchValue(telnet.exec("ZM?", RE_ZM), RE_ZM)
        if (power == "OFF") {
            telnet.exec("ZMON", RE_ZM, 4000)
            update { it.copy(busyMsg = "Powering on receiver (~10 s)...") }
            Thread.sleep(10000)
        }
        val line = telnet.exec("SPPR $n", Regex("^SPPR\\s*$n", RegexOption.IGNORE_CASE), 4000)
        update {
            it.copy(
                preset = matchValue(line, RE_SPPR) ?: n,
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
            telnet.exec("ZM?", RE_ZM) ?: throw Exception("no response from receiver")
            if (serial.isNotEmpty()) {
                AvrRegistry.upsert(AvrRecord(serial, ip, name, model))
            }
            update {
                it.copy(
                    connected = true,
                    deviceIp = ip,
                    deviceName = name.ifEmpty { "AV Receiver" },
                    deviceModel = model,
                    busy = false,
                    busyMsg = "",
                )
            }
            refreshBlocking()
            true
        } catch (e: Exception) {
            telnet.close()
            update { it.copy(busy = false, busyMsg = "", error = "Connection failed: ${e.message}") }
            false
        }
    }

    private fun refreshBlocking() {
        if (!telnet.isAlive()) {
            val ip = state.value.deviceIp
            if (ip.isEmpty()) return
            try {
                telnet.connect(ip)
            } catch (_: Exception) {
                update { it.copy(error = "Connection lost") }
                return
            }
        }
        val power = matchValue(telnet.exec("ZM?", RE_ZM), RE_ZM)
        if (power == null) {
            update { it.copy(error = "No response from receiver") }
            return
        }
        val dynEq = matchValue(telnet.exec("PSDYNEQ ?", RE_DYNEQ), RE_DYNEQ)
        val dynVol = matchValue(telnet.exec("PSDYNVOL ?", RE_DYNVOL), RE_DYNVOL)
        val surLev = matchValue(telnet.exec("PSSURLEV ?", RE_SURLEV), RE_SURLEV)
        val refLev = matchValue(telnet.exec("PSREFLEV ?", RE_REFLEV), RE_REFLEV)
        val multEq = matchValue(telnet.exec("PSMULTEQ: ?", RE_MULTEQ), RE_MULTEQ)
        val preset = matchValue(telnet.exec("SPPR ?", RE_SPPR, 4000), RE_SPPR)
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
