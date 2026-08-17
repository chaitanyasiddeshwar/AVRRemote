package com.avrremote.app

data class DiscoveredDevice(
    val ip: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    val serial: String,
) {
    fun isAvrLike(): Boolean {
        val probe = "$manufacturer $model $name".lowercase()
        return probe.contains("denon") || probe.contains("marantz") ||
            probe.contains("receiver") || probe.contains("avr")
    }
}
