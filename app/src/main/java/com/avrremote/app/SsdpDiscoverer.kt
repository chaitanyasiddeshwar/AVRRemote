package com.avrremote.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

object SsdpDiscoverer {

    private val TARGETS = listOf(
        "urn:schemas-denon-com:device:Receiver:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "upnp:rootdevice",
        "ssdp:all",
    )

    fun discover(timeoutMs: Long = 8000): List<DiscoveredDevice> {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        socket.soTimeout = 500

        val mcast = InetSocketAddress("239.255.255.250", 1900)
        for (st in TARGETS) {
            val msg = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $st\r\n\r\n"
            ).toByteArray()
            socket.send(DatagramPacket(msg, msg.size, mcast))
        }

        val locations = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < deadline) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val text = String(pkt.data, 0, pkt.length, Charsets.ISO_8859_1)
            Regex("LOCATION:\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.trim()?.let {
                    Log.i(TAG, "SSDP: response from ${pkt.address} -> $it")
                    locations.add(it)
                }
        }
        socket.close()
        Log.i(TAG, "SSDP: ${locations.size} unique location(s)")

        val devices = locations
            .mapNotNull { fetchDevice(it) }
            .distinctBy { it.ip }
            .sortedByDescending { it.isAvrLike() }
        Log.i(TAG, "SSDP: ${devices.size} device(s) described")
        return devices
    }

    private fun fetchDevice(location: String): DiscoveredDevice? {
        return try {
            val url = URL(location)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            var serial = tag(xml, "serialNumber")
            if (serial.isEmpty()) {
                val udn = tag(xml, "UDN")
                if (udn.isNotEmpty()) serial = "UDN:$udn"
            }
            DiscoveredDevice(
                ip = url.host,
                name = tag(xml, "friendlyName").ifEmpty { "Unknown device" },
                manufacturer = tag(xml, "manufacturer"),
                model = tag(xml, "modelName"),
                serial = serial,
            )
        } catch (e: Exception) {
            Log.w(TAG, "SSDP: fetch failed for $location: ${e.message}")
            null
        }
    }

    private fun tag(xml: String, name: String): String {
        val re = Regex("<$name>(.*?)</$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return re.find(xml)?.groupValues?.get(1)?.trim() ?: ""
    }

    private const val TAG = "AVRRemote"
}
