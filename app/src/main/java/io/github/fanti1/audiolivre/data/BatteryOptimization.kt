package io.github.fanti1.audiolivre.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

// Android's battery saving may stop an app that runs with the screen off, which is how most people listen.
// An app the user exempts keeps reading until the book is paused.
object BatteryOptimization {

    fun isUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    // Android's own "allow to always run in the background?" question; when a phone lacks it, the list of
    // apps under battery optimization, and failing that the app's own settings page.
    @SuppressLint("BatteryLife")
    fun ask(context: Context) {
        val attempts = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
