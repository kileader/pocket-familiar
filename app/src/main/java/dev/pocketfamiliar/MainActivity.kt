package dev.pocketfamiliar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketfamiliar.ui.FamiliarScreen
import dev.pocketfamiliar.ui.FamiliarViewModel
import dev.pocketfamiliar.ui.FreeRoamScreen
import dev.pocketfamiliar.ui.FreeRoamViewModel
import dev.pocketfamiliar.roam.RoamIdentity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var familiarViewModel: FamiliarViewModel
    private lateinit var freeRoamViewModel: FreeRoamViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val familiarApplication = application as FamiliarApplication
        familiarViewModel = ViewModelProvider(
            this,
            FamiliarViewModel.Factory(familiarApplication.repository, familiarApplication.perception,
                familiarApplication.brain, familiarApplication.brainSettings, familiarApplication.discoveryHistory),
        )[FamiliarViewModel::class.java]
        freeRoamViewModel = ViewModelProvider(this,
            FreeRoamViewModel.Factory(familiarApplication.brainSettings,
                familiarApplication.freeRoamBindings, familiarApplication.freeRoamClient))[FreeRoamViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                familiarViewModel.observePhoneStateChanges()
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFB6D4C4),
                    background = Color(0xFF151C20),
                    surface = Color(0xFF151C20),
                    surfaceContainer = Color(0xFF202B30),
                    onBackground = Color(0xFFE6EEE9),
                    onSurface = Color(0xFFE6EEE9),
                ),
            ) {
                val uiState by familiarViewModel.uiState.collectAsStateWithLifecycle()
                val roamState by freeRoamViewModel.uiState.collectAsStateWithLifecycle()
                var journalOpen by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(journalOpen, uiState.update?.state?.createdAt) {
                    if (journalOpen) uiState.update?.state?.let {
                        freeRoamViewModel.open(RoamIdentity(it.createdAt, it.curiosity))
                    } else freeRoamViewModel.close()
                }
                Surface {
                    if (journalOpen) FreeRoamScreen(roamState,
                        onBack = { journalOpen = false; freeRoamViewModel.close() },
                        onRefresh = freeRoamViewModel::refresh,
                        onConnect = freeRoamViewModel::connect,
                        onSaveControls = freeRoamViewModel::saveControls,
                        onLoadOlder = freeRoamViewModel::loadOlder,
                        onForget = freeRoamViewModel::forget,
                        onDeleteEntry = freeRoamViewModel::deleteEntry)
                    else {
                        FamiliarScreen(
                            state = uiState,
                            onPoke = familiarViewModel::poke,
                            onRefresh = familiarViewModel::refresh,
                            onListen = familiarViewModel::listen,
                            onSaveBrainSettings = { endpoint, token ->
                                val previous = familiarApplication.brainSettings.read()
                                familiarViewModel.saveBrainSettings(endpoint, token)
                                if (previous != familiarApplication.brainSettings.read()) freeRoamViewModel.settingsChanged()
                            },
                            onOpenFreeRoam = { journalOpen = true },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        familiarViewModel.refresh()
        freeRoamViewModel.refreshIfOpen()
    }
}
