package app.ink.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** What the Swift app shows: ink music's screens. */
fun MainViewController(): UIViewController = ComposeUIViewController { Shooting { Box(Modifier.fillMaxSize()) { App(); NeonCoastOverlay(Modifier.fillMaxSize()) } } }
