package com.kkc.sheettracker.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

internal fun parseDeliveryCoordinates(address: String): Pair<Double, Double>? {
    val parts = address.split(",").map { it.trim() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return lat to lng
}

/**
 * Not unit tested: `Uri.parse` is an Android SDK stub under this module's plain JUnit
 * setup (`isReturnDefaultValues = true`), so it returns null in local tests regardless
 * of input. Covered by manual device verification instead (Task 7 of the overall plan).
 */
internal fun deliveryMapsUri(address: String): Uri {
    val coords = parseDeliveryCoordinates(address)
    return if (coords != null) {
        Uri.parse("geo:${coords.first},${coords.second}?q=${coords.first},${coords.second}")
    } else {
        Uri.parse("geo:0,0?q=${URLEncoder.encode(address, "UTF-8")}")
    }
}

internal fun openDeliveryMapsSafely(context: Context, address: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, deliveryMapsUri(address)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No map app is installed. Copy the address instead.", Toast.LENGTH_SHORT).show()
    }
}
