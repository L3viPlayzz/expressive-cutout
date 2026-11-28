package com.ekoehler.expressivecutout

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ekoehler.expressivecutout.core.SystemEventType
import com.ekoehler.expressivecutout.notifications.TestCaller
import com.ekoehler.expressivecutout.notifications.TestNotifier
import kotlinx.coroutines.delay

/** Local developer tools. Frame callbacks measure UI scheduling, not overlay GPU presentation. */
class DevDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Dashboard() }
            }
        }
    }

    private fun notificationTest(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            return
        }
        action()
    }

    @Composable
    private fun Dashboard() {
        var measuring by remember { mutableStateOf(false) }
        var stats by remember { mutableStateOf("Not measuring") }
        var memory by remember { mutableStateOf("—") }
        var stress by remember { mutableStateOf(false) }
        var eventIndex by remember { mutableStateOf(0) }
        val events = SystemEventType.values()
        val refresh = remember { window.decorView.display?.refreshRate ?: 60f }
        LaunchedEffect(measuring) {
            while (measuring) {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                memory = "%.1f MB PSS".format(info.totalPss / 1024.0)
                delay(1000)
            }
        }
        DisposableEffect(measuring) {
            if (!measuring) {
                onDispose { }
            } else {
                val scheduler = Choreographer.getInstance()
                var last = 0L
                var start = 0L
                var frames = 0
                var slow = 0
                var worst = 0.0
                val budget = 1000.0 / refresh.coerceAtLeast(1f)
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        if (start == 0L) start = time
                        if (last != 0L) {
                            val ms = (time - last) / 1e6
                            frames++
                            if (ms > budget * 1.5) slow++
                            worst = maxOf(worst, ms)
                        }
                        last = time
                        val elapsed = (time - start) / 1e9
                        if (elapsed >= 1.0) {
                            stats = "%.1f callbacks/s | %d late intervals | %.1f ms max".format(frames / elapsed, slow, worst)
                            start = time
                            frames = 0
                            slow = 0
                            worst = 0.0
                        }
                        scheduler.postFrameCallback(this)
                    }
                }
                scheduler.postFrameCallback(callback)
                onDispose { scheduler.removeFrameCallback(callback) }
            }
        }
        LaunchedEffect(stress) {
            if (stress) {
                try {
                    repeat(20) { i ->
                        TestNotifier.sendSystemEvent(events[i % events.size], 65)
                        delay(750)
                    }
                } finally { stress = false }
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NEXORA DEV", style = MaterialTheme.typography.headlineMedium)
            Text("Developer dashboard • Android ${Build.VERSION.RELEASE} • ${Build.MODEL}")
            Text("Display mode: %.1f Hz".format(refresh))
            Text("Process memory: $memory")
            Text(stats)
            Text("Frame callbacks are scheduling diagnostics, not measured overlay FPS.")
            Button(onClick = { measuring = !measuring }) { Text(if (measuring) "Stop monitoring" else "Start monitoring") }
            HorizontalDivider()
            Text("Live island event simulator", style = MaterialTheme.typography.titleLarge)
            Text(events[eventIndex].name)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { eventIndex = (eventIndex + events.size - 1) % events.size }) { Text("Previous") }
                OutlinedButton(onClick = { eventIndex = (eventIndex + 1) % events.size }) { Text("Next") }
            }
            Button(onClick = { TestNotifier.sendSystemEvent(events[eventIndex], 65) }) { Text("Trigger selected event") }
            Button(onClick = { stress = !stress }) { Text(if (stress) "Stop stress test" else "Stress test (20 events)") }
            HorizontalDivider()
            Text("Notifications & calls", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { notificationTest { TestNotifier.send(this@DevDashboardActivity) } }) { Text("Test notification") }
            Button(onClick = { notificationTest { TestNotifier.sendMultiline(this@DevDashboardActivity) } }) { Text("Multiline notification") }
            Button(onClick = { notificationTest { TestNotifier.sendPair(this@DevDashboardActivity) } }) { Text("Two notifications") }
            Button(onClick = { notificationTest { TestNotifier.sendProgress(this@DevDashboardActivity) } }) { Text("Progress notification") }
            Button(onClick = { TestCaller.toggle(this@DevDashboardActivity, TestCaller.Kind.INCOMING) }) { Text("Simulate incoming call") }
            Button(onClick = { TestCaller.toggle(this@DevDashboardActivity, TestCaller.Kind.CONNECTED) }) { Text("Simulate ongoing call") }
            OutlinedButton(onClick = { TestCaller.end() }) { Text("End simulated call") }
            Button(onClick = { finish() }) { Text("Back to NEXORA settings") }
        }
    }
}
