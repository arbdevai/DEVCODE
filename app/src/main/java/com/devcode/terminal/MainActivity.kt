package com.devcode.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.devcode.terminal.service.WorkspaceService
import com.devcode.terminal.ui.AppNav
import com.devcode.terminal.ui.theme.DevCodeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DevCodeTheme {
                AppNav(modifier = Modifier.fillMaxSize())
            }
        }

        // Start foreground service to keep chroot sessions alive
        try {
            WorkspaceService.start(this)
        } catch (_: Throwable) {
            // Ignore if foreground service restrictions apply before user interaction
        }
    }
}
