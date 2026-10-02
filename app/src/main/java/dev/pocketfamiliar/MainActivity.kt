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
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketfamiliar.ui.FamiliarScreen
import dev.pocketfamiliar.ui.FamiliarViewModel

class MainActivity : ComponentActivity() {
    private lateinit var familiarViewModel: FamiliarViewModel

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
                familiarApplication.brain, familiarApplication.brainSettings),
        )[FamiliarViewModel::class.java]

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
                Surface {
                    FamiliarScreen(
                        state = uiState,
                        onPoke = familiarViewModel::poke,
                        onRefresh = familiarViewModel::refresh,
                        onListen = familiarViewModel::listen,
                        onSaveBrainSettings = familiarViewModel::saveBrainSettings,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        familiarViewModel.refresh()
    }
}
