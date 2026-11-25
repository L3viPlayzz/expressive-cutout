package com.ekoehler.expressivecutout.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService
import com.ekoehler.expressivecutout.service.CutoutAccessibilityService
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import com.ekoehler.expressivecutout.system.ShizukuState

/**
 * Thin, side-effect-free helpers for querying and requesting the three grants the app
 * needs. Keeping the "is it on?" checks and the "open the right screen" intents together
 * lets the UI stay declarative and re-check state on every resume.
 */
object Permissions {

    /**
     * Asks the notification service itself rather than reading the `Settings.Secure` mirror, which
     * the system only updates asynchronously after a grant and so reads stale right after one.
     */
    fun isNotificationAccessGranted(context: Context): Boolean =
        context.getSystemService<NotificationManager>()
            ?.isNotificationListenerAccessGranted(
                ComponentName(context, CutoutNotificationListenerService::class.java),
            ) == true

    /**
     * Whether the accessibility service is enabled, read from the system's own colon-separated list
     * rather than from the service itself so the answer is right even before it binds.
     */
    fun isAccessibilityGranted(context: Context): Boolean {
        val expected = ComponentName(context, CutoutAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val component = ComponentName.unflattenFromString(expected)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    /**
     * Whether the app is exempt from battery optimisation, which it needs for the island to keep
     * reacting after a long idle.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService<PowerManager>() ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The optional grant behind the phone tile's fast call-end signal. Without it the tile still
     * closes, just from the notification panel rather than from the platform's own call state.
     */
    const val PHONE_STATE: String = Manifest.permission.READ_PHONE_STATE

    fun isPhoneStateGranted(context: Context): Boolean =
        context.checkSelfPermission(PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun openNotificationAccessSettings(context: Context) =
        context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    fun openAccessibilitySettings(context: Context) =
        context.startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /**
     * Opens the direct "ignore battery optimisation" prompt for this app. Falls back to
     * the general list if the OEM blocks the targeted request.
     */
    fun requestIgnoreBatteryOptimization(context: Context) {
        val targeted = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (!context.startActivitySafely(targeted)) {
            context.startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * Opens the installed Shizuku-compatible manager (Shizuku or Shevery) so the user can start it,
     * or the Shizuku store listing when neither is installed. It has to be restarted by hand after
     * every reboot, so this is a link the Status bar screen surfaces often rather than a one-time
     * grant.
     */
    fun openShizuku(context: Context) {
        val launch = ShizukuState.installedManagerPackage(context)
            ?.let(context.packageManager::getLaunchIntentForPackage)
        if (launch != null && context.startActivitySafely(launch)) return
        context.startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_STORE_URL)))
    }

    /**
     * Grants every permission still missing through Shizuku's shell, skipping the ones already
     * held, and reports whether all of them went through. Blocking: call off the main thread, and
     * only when [ShizukuState.status] is [com.ekoehler.expressivecutout.system.ShizukuStatus.READY].
     */
    fun isPostNotificationGranted(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun grantMissingWithShizuku(context: Context): Boolean {
        if (ShizukuState.status.value != com.ekoehler.expressivecutout.system.ShizukuStatus.READY) return false
        val pkg = context.packageName
        val listener = ComponentName(context, CutoutNotificationListenerService::class.java)
            .flattenToString()
        val accessibility = ComponentName(context, CutoutAccessibilityService::class.java)
            .flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val commands = buildList {
            if (!isNotificationAccessGranted(context)) add("cmd notification allow_listener $listener")
            if (!isAccessibilityGranted(context)) {
                val services = (enabledServices.split(':').filter { ComponentName.unflattenFromString(it) != null } + accessibility).distinct()
                val safeServices = services.joinToString(":").replace("'", "'\\''")
                add("settings put secure enabled_accessibility_services '$safeServices'")
                add("settings put secure accessibility_enabled 1")
            }
            if (!isBatteryOptimizationIgnored(context)) add("dumpsys deviceidle whitelist +$pkg")
            if (!isPhoneStateGranted(context)) add("pm grant $pkg $PHONE_STATE")
            if (!isPostNotificationGranted(context)) add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        }
        // Attempt each missing grant even when one is blocked by the device.
        return commands.map(ShizukuState::runAsShell).all { it }
    }

    private fun Context.startActivitySafely(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private const val SHIZUKU_STORE_URL =
        "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
}
