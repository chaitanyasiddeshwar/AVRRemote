package com.avrremote.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class TelnetConnection {

    private var socket: Socket? = null
    private var input: BufferedReader? = null
    private var output: OutputStream? = null

    @Synchronized
    fun isAlive(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
    }

    @Synchronized
    fun connect(ip: String, port: Int = 23, timeoutMs: Int = 4000) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        s.soTimeout = 200
        socket = s
        input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        output = s.getOutputStream()
    }

    /** Sends cmd + "\r", waits for a line matching [expect]. Returns the line or null on timeout/EOF. */
    @Synchronized
    fun exec(cmd: String, expect: Regex? = null, timeoutMs: Long = 3000): String? {
        val out = output
        val reader = input
        if (out == null || reader == null) {
            close()
            return null
        }
        try {
            out.write((cmd + "\r").toByteArray())
            out.flush()
        } catch (e: Exception) {
            close()
            return null
        }
        if (expect == null) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                close()
                return null
            }
            if (line == null) {
                close()
                return null
            }
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && expect.containsMatchIn(trimmed)) return trimmed
        }
        return null
    }

    /** Reads one line. Returns the line, "" on timeout, null on EOF/disconnect. */
    @Synchronized
    fun readLine(timeoutMs: Long): String? {
        val reader = input ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = try {
                reader.readLine()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                close()
                return null
            }
            if (line == null) {
                close()
                return null
            }
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    @Synchronized
    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }
}
