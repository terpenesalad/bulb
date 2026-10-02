package app.halo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.data.SavedDevice
import app.halo.data.Schedule
import app.halo.data.Store
import app.halo.light.LightHub
import app.halo.tuya.BulbState
import app.halo.ui.screens.LightScreen
import app.halo.ui.screens.ScheduleEditor
import app.halo.ui.screens.SchedulesScreen
import app.halo.ui.screens.SettingsScreen
import app.halo.ui.screens.SetupScreen
import app.halo.ui.theme.HaloTheme
import app.halo.ui.theme.LocalHalo
import app.halo.ui.theme.displayColor
import kotlinx.coroutines.flow.MutableStateFlow

private sealed interface Overlay {
    data class Setup(val editing: SavedDevice?) : Overlay
    data class EditSchedule(val schedule: Schedule?) : Overlay
}

@Composable
fun HaloRoot() {
    val data by Store.data.collectAsStateWithLifecycle()
    val controller by LightHub.active.collectAsStateWithLifecycle()
    val fallback = remember { MutableStateFlow(BulbState()) }
    val state by (controller?.state ?: fallback).collectAsStateWithLifecycle()
    val settings = data.settings
    val accent = if (settings.adaptiveAccent && controller != null && state.on) state.displayColor() else Color(settings.accent)

    HaloTheme(settings.theme, accent) {
        val halo = LocalHalo.current
        var tab by rememberSaveable { mutableStateOf(0) }
        var overlay by remember { mutableStateOf<Overlay?>(null) }
        BackHandler(enabled = overlay != null) { overlay = null }
        BackHandler(enabled = overlay == null && tab != 0) { tab = 0 }

        Box(Modifier.fillMaxSize().background(halo.background)) {
            val current = overlay
            val ctrl = controller
            when {
                data.devices.isEmpty() || current is Overlay.Setup -> {
                    val editing = (current as? Overlay.Setup)?.editing
                    SetupScreen(
                        data = data,
                        editing = editing,
                        onDone = { overlay = null },
                        onCancel = if (data.devices.isEmpty()) null else ({ overlay = null }),
                    )
                }
                current is Overlay.EditSchedule -> ScheduleEditor(data, current.schedule) { overlay = null }
                ctrl != null -> Scaffold(
                    containerColor = halo.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar(containerColor = halo.surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                            listOf(
                                Triple("Light", Icons.Rounded.Lightbulb, 0),
                                Triple("Schedules", Icons.Rounded.Schedule, 1),
                                Triple("Settings", Icons.Rounded.Tune, 2),
                            ).forEach { (label, icon, i) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("nav-$label"),
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = halo.text, selectedTextColor = halo.text,
                                        indicatorColor = halo.accent.copy(alpha = 0.22f),
                                        unselectedIconColor = halo.textDim, unselectedTextColor = halo.textDim,
                                    ),
                                )
                            }
                        }
                    },
                ) { pad ->
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 40 }) togetherWith fadeOut(tween(120)) },
                        modifier = Modifier.padding(pad),
                        label = "tabs",
                    ) { t ->
                        when (t) {
                            0 -> LightScreen(
                                data, ctrl,
                                onAddLight = { overlay = Overlay.Setup(null) },
                                onEditLight = { overlay = Overlay.Setup(ctrl.device.let { d -> data.devices.firstOrNull { it.id == d.id } ?: d }) },
                            )
                            1 -> SchedulesScreen(data) { overlay = Overlay.EditSchedule(it) }
                            else -> SettingsScreen(data, onEditLight = { overlay = Overlay.Setup(it) }, onAddLight = { overlay = Overlay.Setup(null) })
                        }
                    }
                }
            }
        }
    }
}
