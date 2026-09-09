package app.pawse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.feature.onboarding.OnboardingScreen
import app.pawse.ui.HomePlaceholderScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncPreferences: SyncPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge and predictive back are platform mechanics, not style
        // choices. This app is native Android; it does not imitate iOS chrome.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            PawseTheme {
                PawseRoot(syncPreferences)
            }
        }
    }
}

@Composable
private fun PawseRoot(preferences: SyncPreferences) {
    val onboarded by preferences.onboardingComplete.collectAsState(initial = false)
    var forceHome by remember { mutableStateOf(false) }

    if (onboarded || forceHome) {
        HomePlaceholderScreen()
    } else {
        OnboardingScreen(onFinished = { forceHome = true })
    }
}
