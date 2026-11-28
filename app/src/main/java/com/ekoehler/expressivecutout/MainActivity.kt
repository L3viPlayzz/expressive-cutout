package com.ekoehler.expressivecutout

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ekoehler.expressivecutout.permissions.Permissions
import com.ekoehler.expressivecutout.service.CutoutNotificationListenerService
import com.ekoehler.expressivecutout.system.AppLocale
import com.ekoehler.expressivecutout.ui.AppViewModel
import com.ekoehler.expressivecutout.ui.MainScreen
import com.ekoehler.expressivecutout.ui.theme.ExpressiveCutoutTheme
import com.ekoehler.expressivecutout.ui.theme.isDark

/** Single-activity host. The overlay itself runs independently in the services. */
class MainActivity : ComponentActivity() {

    /** Localises the whole settings UI to the picked language. See [AppLocale]. */
    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(AppLocale.wrap(newBase))

    /**
     * Hosts the whole settings UI: one edge-to-edge activity with the Compose tree rooted here, so
     * the system bars follow the app theme.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        setContent {
            val viewModel: AppViewModel = viewModel()
            val theme by viewModel.theme.collectAsStateWithLifecycle()
            val darkTheme = theme.isDark()

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            ExpressiveCutoutTheme(appTheme = theme) {
                androidx.compose.foundation.layout.Box {
                    MainScreen(viewModel)
                    androidx.compose.material3.FloatingActionButton(
                        onClick = { startActivity(android.content.Intent(this@MainActivity, DevDashboardActivity::class.java)) },
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 16.dp, bottom = 100.dp)
                    ) { androidx.compose.material3.Text("DEV") }
                }
            }
        }
    }

    /**
     * Re-arms the notification listener on the way back into the app. The framework silently
     * unbinds it after an app update while the grant still reads as given, which would leave every
     * dynamic tile starved with nothing to show the user is wrong.
     */
    override fun onResume() {
        super.onResume()

        if (Permissions.isNotificationAccessGranted(this) &&
            !CutoutNotificationListenerService.bound.value
        ) {
            CutoutNotificationListenerService.requestRebind(this)
        }
    }
}
