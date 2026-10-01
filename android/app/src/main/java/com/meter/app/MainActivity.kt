package com.meter.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meter.app.core.FamilyFilter
import com.meter.app.service.MeterService
import com.meter.app.state.MeterEngine
import com.meter.app.ui.MeterScreen
import com.meter.app.ui.SetupScreen
import com.meter.app.ui.theme.MeterColors
import com.meter.app.ui.theme.MeterTheme

/**
 * Two screens, one process. The Meter page is the design; long-pressing its top bar (or tapping
 * the dashed pill) opens Setup. Both read the same [MeterEngine] snapshot, so the Setup page is
 * always editing exactly the source the Meter page is showing.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            MeterEngine.refreshPermissions()
        }

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            MeterEngine.noteVpnPermission(result.resultCode == RESULT_OK)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeterEngine.init(this)

        // First run: the meter should keep counting whether or not the screen is on.
        if (!MeterEngine.state.value.meteringPaused) MeterService.start(this)

        setContent {
            MeterTheme {
                var screen by rememberSaveable { mutableStateOf(SCREEN_METER) }
                val state by MeterEngine.state.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MeterColors.Bg)
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    if (screen == SCREEN_METER) {
                        MeterScreen(
                            state = state,
                            onFilter = { MeterEngine.setFilter(it) },
                            onSelect = { MeterEngine.select(it) },
                            onReset = { MeterEngine.resetCycle(it) },
                            onOpenSetup = { screen = SCREEN_SETUP }
                        )
                    } else {
                        SetupScreen(
                            state = state,
                            onBack = { screen = SCREEN_METER },
                            onSelect = { MeterEngine.select(it) },
                            onConfig = { id, transform -> MeterEngine.updateConfig(id, transform) },
                            onClear = { MeterEngine.clearCounters(it) },
                            onManualBlock = { id, blocked -> MeterEngine.setManualBlock(id, blocked) },
                            onPreviewAlert = { id, percent -> MeterEngine.previewAlert(id, percent) },
                            onPause = { MeterEngine.setPaused(it) },
                            onKillSwitch = { MeterEngine.setKillSwitchEnabled(it) },
                            onSampleMillis = { MeterEngine.setSampleMillis(it) },
                            onStartService = { MeterService.start(this@MainActivity) },
                            onStopService = { MeterService.stop(this@MainActivity) },
                            onRequestNotifications = { askNotifications() },
                            onRequestUsageAccess = { openUsageAccessSettings() },
                            onRequestLocation = { ask(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) },
                            onRequestPhoneState = { ask(arrayOf(Manifest.permission.READ_PHONE_STATE)) },
                            onRequestVpn = { askVpn() },
                            onRequestBatteryExemption = { askBatteryExemption() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        MeterEngine.init(this)
        MeterEngine.refreshPermissions()
        MeterEngine.refreshSources()
        MeterEngine.startSampling()
    }

    override fun onPause() {
        // The service keeps sampling for us; when it is not running this simply stops the loop.
        MeterEngine.stopSampling()
        super.onPause()
    }

    // ------------------------------------------------------------- permissions

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun ask(permissions: Array<String>) {
        runCatching { permissionLauncher.launch(permissions) }
    }

    private fun askVpn() {
        val prepare: Intent? = runCatching { VpnService.prepare(this) }.getOrNull()
        if (prepare == null) {
            MeterEngine.noteVpnPermission(true)
        } else {
            runCatching { vpnLauncher.launch(prepare) }
        }
    }

    /**
     * Usage access is what lets the meter read the platform's own per-subscription ledger — the
     * figure behind Settings → Data usage — so it can recover traffic it could not see.
     */
    private fun openUsageAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
    }

    private fun askBatteryExemption() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private companion object {
        const val SCREEN_METER = "meter"
        const val SCREEN_SETUP = "setup"
    }
}
