package com.avrremote.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AvrRecord(
    var serial: String,
    var ip: String,
    var name: String,
    var model: String,
)

object AvrRegistry {

    private var file: File? = null
    private val records = mutableListOf<AvrRecord>()
    var active: String? = null
        private set

    val avrs: List<AvrRecord> get() = records.toList()

    fun init(context: Context) {
        file = File(context.filesDir, "avr_registry.json")
        load()
    }

    fun activeRecord(): AvrRecord? = records.firstOrNull { it.serial == active }

    fun upsert(rec: AvrRecord) {
        val existing = records.firstOrNull { it.serial == rec.serial }
        if (existing != null) {
            existing.ip = rec.ip
            if (rec.name.isNotEmpty()) existing.name = rec.name
            if (rec.model.isNotEmpty()) existing.model = rec.model
        } else {
            records.add(rec)
        }
        active = rec.serial
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            active = root.optString("active").ifEmpty { null }
            records.clear()
            val arr = root.optJSONArray("avrs") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val serial = o.optString("serial")
                if (serial.isEmpty()) continue
                records.add(
                    AvrRecord(
                        serial = serial,
                        ip = o.optString("ip"),
                        name = o.optString("name"),
                        model = o.optString("model"),
                    )
                )
            }
        } catch (_: Exception) {
            records.clear()
            active = null
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (r in records) {
                arr.put(
                    JSONObject()
                        .put("serial", r.serial)
                        .put("ip", r.ip)
                        .put("name", r.name)
                        .put("model", r.model)
                )
            }
            val root = JSONObject().put("avrs", arr).put("active", active ?: JSONObject.NULL)
            f.writeText(root.toString(2))
        } catch (_: Exception) {
        }
    }
}
