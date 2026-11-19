package com.erashon.sharedrop.discovery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject

/** Información del teléfono que se anuncia a los demás equipos (todos los campos son opcionales). */
object DeviceInfo {
    fun collect(ctx: Context, link: String? = null): JSONObject {
        val j = JSONObject()
            .put("model", Build.MODEL)
            .put("maker", Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            .put("os", "Android ${Build.VERSION.RELEASE}")
        // Por qué medio está conectado el teléfono: "usb", "wifi" o "hotspot".
        link?.let { j.put("link", it) }

        // Intent "pegajoso": no hace falta registrar un receptor para leer el estado actual.
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) j.put("battery", level * 100 / scale)
            val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            j.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        }

        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            j.put("free", s.availableBytes).put("total", s.totalBytes)
        }
        return j
    }
}
