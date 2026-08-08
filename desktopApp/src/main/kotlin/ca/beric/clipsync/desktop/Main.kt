package ca.beric.clipsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.core.MacPasteboard
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private fun trayIcon(): BitmapPainter {
    val bitmap = ImageBitmap(64, 64)
    val paint = Paint().apply { color = Color(0xFF2AA198) }
    Canvas(bitmap).drawRect(0f, 0f, 64f, 64f, paint)
    return BitmapPainter(bitmap)
}

fun main() {
    val repo = ClipRepository(ClipsyncDb(DriverFactory().createDriver()))
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    appScope.launch {
        ClipboardWatcher(MacPasteboard()).changes().collect { text ->
            repo.record(LOCAL_DEVICE_ID, text, System.currentTimeMillis())
        }
    }

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { trayIcon() }

        Tray(
            icon = icon,
            tooltip = "clipsync",
            onAction = { windowVisible = true },
            menu = {
                Item("Open history") { windowVisible = true }
                Separator()
                Item("Quit clipsync") { exitApplication() }
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = { windowVisible = false },
                title = "clipsync history",
                state = rememberWindowState(width = 380.dp, height = 520.dp),
            ) {
                HistoryScreen(repo)
            }
        }
    }
}
