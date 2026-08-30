package com.avrremote.app

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class ChannelSetting(
    val id: String,                  // e.g. "FL", "C", "FR", "SL", "SR", "SW1"
    val name: String,                // e.g. "Front Left", "Center", "Front Right", "Subwoofer 1"
    val levelDb: Float? = null,      // e.g. 0.0f, +1.5f, -2.0f (parsed from SSLEV)
    val levelRaw: String? = null,    // e.g. "50", "515"
    val trimDb: Float? = null,       // e.g. 0.0f, +3.0f (parsed from CV)
    val trimRaw: String? = null,     // e.g. "50", "53"
    val distanceVal: Float? = null,  // in meters or feet (e.g. 3.00f)
    val distanceUnit: String = "m",  // "m" or "ft"
    val distanceRaw: String? = null, // e.g. "0300"
    val crossover: String? = null,   // e.g. "Full Band", "80 Hz", "120 Hz", "LPF: 120 Hz"
    val crossoverRaw: String? = null,// e.g. "FUL", "080"
)

data class ChannelSettingsSummary(
    val channels: List<ChannelSetting> = emptyList(),
    val distanceUnit: String = "m",
    val lpfLfe: String? = null,      // e.g. "120 Hz", "250 Hz" (from SSLFL)
    val subMode: String? = null,     // e.g. "LFE", "LFE + Main" (from SSSWO)
    val lastUpdatedMs: Long = 0L,
    val rawLines: List<String> = emptyList(),
)

object AvrBinaryProtocol {
    // 54 00 13 00 00 47 45 54 5f 41 56 52 53 54 53 00 00 00 89 (19 bytes)
    private val CMD_GET_AVRSTS = byteArrayOf(
        0x54, 0x00, 0x13, 0x00, 0x00,
        0x47, 0x45, 0x54, 0x5f, 0x41, 0x56, 0x52, 0x53, 0x54, 0x53, // "GET_AVRSTS"
        0x00, 0x00, 0x00, 0x89.toByte(),
    )

    fun fetchActiveChannels(ip: String, port: Int = 1256, timeoutMs: Int = 2000): List<String>? {
        if (ip.isBlank()) return null
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val out = socket.getOutputStream()
            out.write(CMD_GET_AVRSTS)
            out.flush()

            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            val baos = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    break
                }
                if (read <= 0) break
                baos.write(buffer, 0, read)
                val str = baos.toString("UTF-8")
                if (str.contains("ChSetup") && str.contains("]")) {
                    break
                }
            }

            val rawData = baos.toByteArray()
            if (rawData.isEmpty()) return null
            val jsonStr = extractJsonFromFrame(rawData) ?: return null
            return parseActiveChannelsFromJson(jsonStr)
        } catch (_: Exception) {
            return null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun extractJsonFromFrame(bytes: ByteArray): String? {
        val str = String(bytes, Charsets.ISO_8859_1)
        val firstBrace = str.indexOf('{')
        val lastBrace = str.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return str.substring(firstBrace, lastBrace + 1)
        }
        return null
    }

    fun parseActiveChannelsFromJson(jsonStr: String): List<String> {
        val result = mutableListOf<String>()
        // Parse ChSetup array entries e.g. [{"FL":"L"},{"C":"S"},{"FR":"L"},{"SLA":"S"},{"SRA":"S"},{"SBL":"N"},{"SW1":"Y"}]
        val chSetupIdx = jsonStr.indexOf("\"ChSetup\"")
        if (chSetupIdx >= 0) {
            val arrayStart = jsonStr.indexOf('[', chSetupIdx)
            val arrayEnd = jsonStr.indexOf(']', arrayStart)
            if (arrayStart >= 0 && arrayEnd > arrayStart) {
                val arrayContent = jsonStr.substring(arrayStart + 1, arrayEnd)
                val itemRegex = Regex("\"([A-Za-z0-9]+)\"\\s*:\\s*\"([A-Za-z0-9]+)\"")
                for (match in itemRegex.findAll(arrayContent)) {
                    val ch = match.groupValues[1]
                    val state = match.groupValues[2].uppercase()
                    if (state != "N" && state.isNotEmpty()) {
                        result.add(ch)
                    }
                }
            }
        }

        // Check SWNum in SWSetup e.g. "SWNum": 2
        val swNumMatch = Regex("\"SWNum\"\\s*:\\s*(\\d+)").find(jsonStr)
        val swNum = swNumMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (swNum > 0 && !result.any { it.startsWith("SW", ignoreCase = true) }) {
            for (s in 1..swNum) {
                result.add("SW$s")
            }
        }
        return result
    }
}

object ChannelSettingsParser {

    val RE_SSSDE = Regex("^SSSDE([A-Z0-9]+)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    val RE_SSCFR = Regex("^SSCFR([A-Z0-9]+)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    val RE_SSLEV = Regex("^SSLEV([A-Z0-9]+)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    val RE_CV = Regex("^CV([A-Z0-9]+)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    val RE_SSSPC = Regex("^SSSPC([A-Z0-9]+)\\s+(\\S+)", RegexOption.IGNORE_CASE)
    val RE_SSLFL = Regex("^SSLFL\\s*(\\d+)", RegexOption.IGNORE_CASE)
    val RE_SSSWO = Regex("^SSSWO\\s*(\\S+)", RegexOption.IGNORE_CASE)

    // Standard ordering for multi-channel home theater speaker layouts
    private val CHANNEL_ORDER = listOf(
        "FL", "C", "FR",
        "SR", "SBR", "SB", "SBL", "SL",
        "FHL", "FHR",
        "TFL", "TFR",
        "TML", "TMR",
        "TRL", "TRR",
        "RHL", "RHR",
        "FDL", "FDR",
        "SDL", "SDR",
        "BDL", "BDR",
        "SHL", "SHR",
        "FWL", "FWR",
        "TS", "CH",
        "SW", "SW1", "SW2", "SW3", "SW4",
    )

    private val CHANNEL_NAMES = mapOf(
        "FL" to "Front Left",
        "FR" to "Front Right",
        "C" to "Center",
        "SL" to "Surround Left",
        "SR" to "Surround Right",
        "SBL" to "Surround Back Left",
        "SBR" to "Surround Back Right",
        "SB" to "Surround Back",
        "FHL" to "Front Height Left",
        "FHR" to "Front Height Right",
        "TFL" to "Top Front Left",
        "TFR" to "Top Front Right",
        "TML" to "Top Middle Left",
        "TMR" to "Top Middle Right",
        "TRL" to "Top Rear Left",
        "TRR" to "Top Rear Right",
        "RHL" to "Rear Height Left",
        "RHR" to "Rear Height Right",
        "FDL" to "Front Dolby Left",
        "FDR" to "Front Dolby Right",
        "SDL" to "Surround Dolby Left",
        "SDR" to "Surround Dolby Right",
        "BDL" to "Back Dolby Left",
        "BDR" to "Back Dolby Right",
        "SHL" to "Surround Height Left",
        "SHR" to "Surround Height Right",
        "FWL" to "Front Wide Left",
        "FWR" to "Front Wide Right",
        "TS" to "Top Surround",
        "CH" to "Center Height",
        "SW" to "Subwoofer 1",
        "SW1" to "Subwoofer 1",
        "SW2" to "Subwoofer 2",
        "SW3" to "Subwoofer 3",
        "SW4" to "Subwoofer 4",
    )

    fun normalizeChannelId(rawId: String): String {
        val upper = rawId.uppercase().trim()
        return when (upper) {
            "SLA" -> "SL"
            "SRA" -> "SR"
            "SW" -> "SW1"
            "SWLFE", "SWLFE1", "SWMIX1" -> "SW1"
            "SWLFE2", "SWMIX2" -> "SW2"
            "SWLFE3", "SWMIX3" -> "SW3"
            "SWLFE4", "SWMIX4" -> "SW4"
            else -> upper
        }
    }

    fun getDisplayName(id: String): String {
        val norm = normalizeChannelId(id)
        return CHANNEL_NAMES[norm] ?: CHANNEL_NAMES[id.uppercase()] ?: id
    }

    fun getSortIndex(id: String): Int {
        val norm = normalizeChannelId(id)
        val idx = CHANNEL_ORDER.indexOf(norm)
        return if (idx >= 0) idx else 100
    }

    fun parseDenonLevel(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val floatVal = trimmed.toFloatOrNull() ?: return null
        return when {
            trimmed.contains(".") -> floatVal - 50.0f
            trimmed.length == 3 -> (floatVal - 500.0f) / 10.0f
            trimmed.length <= 2 -> floatVal - 50.0f
            else -> (floatVal - 500.0f) / 10.0f
        }
    }

    fun formatLevel(db: Float?): String {
        if (db == null) return "--"
        return when {
            db > 0.001f -> "+%.1f dB".format(db)
            db < -0.001f -> "%.1f dB".format(db)
            else -> "0.0 dB"
        }
    }

    fun parseDistance(raw: String?, unit: String): Float? {
        if (raw.isNullOrBlank()) return null
        val num = raw.trim().toIntOrNull() ?: return null
        if (num == 0) return null // 0000 means unconfigured/absent

        return if (unit.equals("ft", ignoreCase = true)) {
            if (num >= 1000) num / 100.0f else num / 10.0f
        } else {
            // Metric: Denon sends cm in 4 digits (e.g. 0300 = 300 cm = 3.00 m)
            num / 100.0f
        }
    }

    fun formatDistance(distVal: Float?, unit: String = "m"): String {
        if (distVal == null || distVal <= 0.001f) return "--"
        return if (unit.equals("ft", ignoreCase = true)) {
            val meters = distVal * 0.3048f
            "%.1f ft (%.2f m)".format(distVal, meters)
        } else {
            val feet = distVal / 0.3048f
            "%.2f m (%.1f ft)".format(distVal, feet)
        }
    }

    fun parseCrossover(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val upper = raw.trim().uppercase()
        if (upper == "FUL" || upper == "FULL" || upper == "LARGE") return "Full Band"
        val hz = upper.toIntOrNull()
        if (hz != null) return "$hz Hz"
        return upper
    }

    private fun getCrossoverGroupCodes(channelId: String): List<String> {
        val norm = normalizeChannelId(channelId)
        return when (norm) {
            "FL", "FR" -> listOf("FRO", "FL", "FR", "FRONT")
            "C" -> listOf("CEN", "C", "CENTER")
            "SL", "SR" -> listOf("SUA", "SUR", "SL", "SR", "SURROUND")
            "SBL", "SBR", "SB" -> listOf("SBK", "SBL", "SBR", "SB", "SURR_BACK")
            "FHL", "FHR" -> listOf("FRH", "FHL", "FHR", "FRONT_HEIGHT")
            "TFL", "TFR" -> listOf("TFR", "TFL", "TOP_FRONT")
            "TML", "TMR" -> listOf("TPM", "TML", "TMR", "TOP_MIDDLE")
            "TRL", "TRR" -> listOf("TPR", "TRL", "TRR", "TOP_REAR")
            "RHL", "RHR" -> listOf("RHE", "RHL", "RHR", "REAR_HEIGHT")
            "FDL", "FDR" -> listOf("FRD", "FDL", "FDR", "FRONT_DOLBY")
            "SDL", "SDR" -> listOf("SUD", "SDL", "SDR", "SURR_DOLBY")
            "BDL", "BDR" -> listOf("BKD", "BDL", "BDR", "BACK_DOLBY")
            "SHL", "SHR" -> listOf("SHE", "SHL", "SHR", "SURR_HEIGHT")
            "FWL", "FWR" -> listOf("FWD", "FWL", "FWR", "FRONT_WIDE")
            "TS" -> listOf("TPS", "TS", "TOP_SURROUND")
            "CH" -> listOf("CEH", "CH", "CENTER_HEIGHT")
            "SW1", "SW2", "SW3", "SW4" -> emptyList()
            else -> listOf(norm)
        }
    }

    fun parseActiveChannelsFromSspc(sspcLines: List<String>): List<String> {
        val activeChannels = mutableListOf<String>()
        var swCount = 0

        for (line in sspcLines) {
            val m = RE_SSSPC.find(line) ?: continue
            val group = m.groupValues[1].uppercase()
            val value = m.groupValues[2].uppercase()

            when (group) {
                "FRO" -> if (value == "YES") { activeChannels.add("FL"); activeChannels.add("FR") }
                "CEN" -> if (value == "YES") activeChannels.add("C")
                "SUA", "SUR" -> if (value == "YES") { activeChannels.add("SL"); activeChannels.add("SR") }
                "SBK" -> {
                    when (value) {
                        "YES", "2SP" -> { activeChannels.add("SBL"); activeChannels.add("SBR") }
                        "1SP" -> activeChannels.add("SB")
                    }
                }
                "SWF" -> {
                    when (value) {
                        "1SP" -> swCount = maxOf(swCount, 1)
                        "2SP" -> swCount = maxOf(swCount, 2)
                        "3SP" -> swCount = maxOf(swCount, 3)
                        "4SP" -> swCount = maxOf(swCount, 4)
                    }
                }
                "FRH" -> if (value == "YES") { activeChannels.add("FHL"); activeChannels.add("FHR") }
                "TFR" -> if (value == "YES") { activeChannels.add("TFL"); activeChannels.add("TFR") }
                "TPM" -> if (value == "YES") { activeChannels.add("TML"); activeChannels.add("TMR") }
                "TPR" -> if (value == "YES") { activeChannels.add("TRL"); activeChannels.add("TRR") }
                "RHE" -> if (value == "YES") { activeChannels.add("RHL"); activeChannels.add("RHR") }
                "FRD" -> if (value == "YES") { activeChannels.add("FDL"); activeChannels.add("FDR") }
                "SUD" -> if (value == "YES") { activeChannels.add("SDL"); activeChannels.add("SDR") }
                "BKD" -> if (value == "YES") { activeChannels.add("BDL"); activeChannels.add("BDR") }
                "SHE" -> if (value == "YES") { activeChannels.add("SHL"); activeChannels.add("SHR") }
                "FWD" -> if (value == "YES") { activeChannels.add("FWL"); activeChannels.add("FWR") }
                "TPS" -> if (value == "YES") activeChannels.add("TS")
                "CEH" -> if (value == "YES") activeChannels.add("CH")
            }
        }

        if (swCount > 0) {
            for (i in 1..swCount) {
                activeChannels.add("SW$i")
            }
        } else if (sspcLines.any { it.contains("SSSPCSWM", ignoreCase = true) && !it.contains("OFF", ignoreCase = true) }) {
            activeChannels.add("SW1")
        }

        return activeChannels
    }

    fun parseActiveChannelsFromCv(cvLines: List<String>): List<String> {
        return cvLines.mapNotNull { line ->
            val m = RE_CV.find(line) ?: return@mapNotNull null
            val code = m.groupValues[1].uppercase()
            if (code == "END" || code == "ZRL") null else normalizeChannelId(code)
        }
    }

    fun parseChannelSettings(
        distanceLines: List<String>,
        crossoverLines: List<String>,
        levelLines: List<String>,
        trimLines: List<String>,
        sspcLines: List<String> = emptyList(),
        lpfLfeLine: String? = null,
        subModeLine: String? = null,
        port1256ActiveChannels: List<String>? = null,
    ): ChannelSettingsSummary {
        val distances = mutableMapOf<String, String>()
        var distanceUnit = "m"

        for (line in distanceLines) {
            val m = RE_SSSDE.find(line) ?: continue
            val key = m.groupValues[1].uppercase()
            val value = m.groupValues[2]
            if (key == "STP") {
                if (value.contains("F", ignoreCase = true)) {
                    distanceUnit = "ft"
                } else if (value.contains("M", ignoreCase = true)) {
                    distanceUnit = "m"
                }
            } else {
                distances[normalizeChannelId(key)] = value
            }
        }

        val crossovers = mutableMapOf<String, String>()
        for (line in crossoverLines) {
            val m = RE_SSCFR.find(line) ?: continue
            val key = m.groupValues[1].uppercase()
            val value = m.groupValues[2]
            crossovers[key] = value
        }

        val levels = mutableMapOf<String, String>()
        for (line in levelLines) {
            val m = RE_SSLEV.find(line) ?: continue
            val key = m.groupValues[1].uppercase()
            if (key == "END") continue
            val value = m.groupValues[2]
            levels[normalizeChannelId(key)] = value
        }

        val trims = mutableMapOf<String, String>()
        for (line in trimLines) {
            val m = RE_CV.find(line) ?: continue
            val key = m.groupValues[1].uppercase()
            if (key == "END") continue
            val value = m.groupValues[2]
            trims[normalizeChannelId(key)] = value
        }

        val lpfLfeVal = lpfLfeLine?.let { line ->
            RE_SSLFL.find(line)?.groupValues?.getOrNull(1)?.let { "$it Hz" }
        }

        val subModeVal = subModeLine?.let { line ->
            RE_SSSWO.find(line)?.groupValues?.getOrNull(1)?.let {
                when (it.uppercase()) {
                    "LFE" -> "LFE"
                    "L+M", "LFE+MAIN" -> "LFE + Main"
                    else -> it
                }
            }
        }

        // Determine the authoritative active/connected channel set
        val activeSet: Set<String> = when {
            !port1256ActiveChannels.isNullOrEmpty() -> {
                port1256ActiveChannels.map { normalizeChannelId(it) }.toSet()
            }
            sspcLines.isNotEmpty() -> {
                val fromSspc = parseActiveChannelsFromSspc(sspcLines).map { normalizeChannelId(it) }.toSet()
                if (fromSspc.isNotEmpty()) fromSspc else {
                    val fromCv = parseActiveChannelsFromCv(trimLines).toSet()
                    if (fromCv.isNotEmpty()) fromCv else (distances.keys + levels.keys + trims.keys).toSet()
                }
            }
            trimLines.isNotEmpty() -> {
                val fromCv = parseActiveChannelsFromCv(trimLines).toSet()
                if (fromCv.isNotEmpty()) {
                    // Merge subs if distance/levels show sub
                    val subs = distances.keys.filter { it.startsWith("SW") }
                    fromCv + subs.take(1)
                } else {
                    (distances.keys + levels.keys + trims.keys).toSet()
                }
            }
            else -> {
                // Fallback: exclude zero distance channels
                (distances.keys + levels.keys + trims.keys).filter { ch ->
                    val dist = distances[ch]
                    dist != null && dist != "0000" && dist != "0"
                }.toSet()
            }
        }

        val activeChannelKeys = activeSet.sortedWith(compareBy({ getSortIndex(it) }, { it }))

        val channelSettings = activeChannelKeys.map { chId ->
            val rawDist = distances[chId]
            val distVal = parseDistance(rawDist, distanceUnit)

            val rawLvl = levels[chId]
            val lvlDb = parseDenonLevel(rawLvl)

            val rawTrim = trims[chId]
            val trimDb = parseDenonLevel(rawTrim)

            val isSub = chId.startsWith("SW")
            val xoverRaw = if (isSub) {
                null
            } else {
                val groupCodes = getCrossoverGroupCodes(chId)
                groupCodes.firstNotNullOfOrNull { crossovers[it] } ?: crossovers[chId]
            }
            val xoverFormatted = if (isSub) {
                if (lpfLfeVal != null) "LPF: $lpfLfeVal" else "N/A"
            } else {
                parseCrossover(xoverRaw) ?: "--"
            }

            ChannelSetting(
                id = chId,
                name = getDisplayName(chId),
                levelDb = lvlDb,
                levelRaw = rawLvl,
                trimDb = trimDb,
                trimRaw = rawTrim,
                distanceVal = distVal,
                distanceUnit = distanceUnit,
                distanceRaw = rawDist,
                crossover = xoverFormatted,
                crossoverRaw = xoverRaw,
            )
        }

        val allRawLines = (distanceLines + crossoverLines + levelLines + trimLines + sspcLines + listOfNotNull(lpfLfeLine, subModeLine))

        return ChannelSettingsSummary(
            channels = channelSettings,
            distanceUnit = distanceUnit,
            lpfLfe = lpfLfeVal,
            subMode = subModeVal,
            lastUpdatedMs = System.currentTimeMillis(),
            rawLines = allRawLines,
        )
    }
}
