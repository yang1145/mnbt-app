package com.mnbt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mnbt.app.ui.AppRoot
import com.mnbt.app.ui.theme.MNBTappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = appContainer.settings
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            MNBTappTheme(themeMode = themeMode) {
                AppRoot()
            }
        }
    }
}
