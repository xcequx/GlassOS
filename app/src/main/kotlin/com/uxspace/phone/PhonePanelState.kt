package com.uxspace.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uxspace.apps.InstalledApp
import com.uxspace.hub.HubComputer
import com.uxspace.hub.HubDesktop
import com.uxspace.glasses.GlassesSnapshot
import com.uxspace.glasses.TrackingKind
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.Layout

enum class PanelScene { SETUP, WAITING, CONTROL }

enum class ControlTab { TOUCHPAD, APPS, WORKSPACE, AI, SETTINGS }

enum class WorkspacePreset(val label: String, val hint: String) {
    FOCUS("Focus", "Jeden ekran przyklejony do głowy"),
    ONE("1 ekran", "Pojedynczy pulpit w przestrzeni"),
    TWO("2 ekrany", "Mail + przeglądarka obok siebie"),
    THREE("3 ekrany", "Trzy wirtualne monitory"),
    CINEMA("Kino", "Szeroki zakrzywiony ekran"),
}

data class SetupContent(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
)

class PhonePanelState {
    var scene by mutableStateOf(PanelScene.SETUP)
    var setup by mutableStateOf(
        SetupContent("GlassOS", "Uruchamianie…"),
    )
    var glasses by mutableStateOf<GlassesSnapshot?>(null)
    var dofActive by mutableStateOf(false)
    var viewModePinned by mutableStateOf(true)
    var layout by mutableStateOf(Layout.SINGLE)
    var headCursor by mutableStateOf(false)
    var zoomPercent by mutableIntStateOf(120)
    var tab by mutableStateOf(ControlTab.TOUCHPAD)
    var apps by mutableStateOf<List<InstalledApp>>(emptyList())
    var keyboardVisible by mutableStateOf(false)
    var privilegeReady by mutableStateOf(false)
    var brightness by mutableIntStateOf(-1)
    var filmPercent by mutableFloatStateOf(0f)
    var stereo3d by mutableStateOf(false)
    var taskbarVisible by mutableStateOf(true)
    var statusLine by mutableStateOf("")
    var cameraUsb by mutableStateOf(false)
    var cameraLabel by mutableStateOf("kamera: —")
    var cameraStreaming by mutableStateOf(false)
    var cameraSource by mutableStateOf("—")
    var cameraSources by mutableStateOf<List<String>>(emptyList())
    var selectedCamera by mutableStateOf("")
    var aiGatewayUrl by mutableStateOf("https://desktop-sotkr5k.tail37a666.ts.net")
    var aiGatewayStatus by mutableStateOf("nie sprawdzono")
    var aiBusy by mutableStateOf(false)
    var aiAnswer by mutableStateOf("")
    var aiFramesSent by mutableIntStateOf(0)
    var aiLive by mutableStateOf(false)
    /** Workspace + tracking health, mirrored from the diagnostics the hub also gets. */
    var workspaceOn by mutableStateOf(false)
    var workspaceHint by mutableStateOf("")
    var trackingHint by mutableStateOf("")
    var hubConnected by mutableStateOf(false)
    var hubStatus by mutableStateOf("hub wyłączony")
    var hubDesktops by mutableStateOf<List<HubDesktop>>(emptyList())
    var hubComputers by mutableStateOf<List<HubComputer>>(emptyList())
    /** Desktop id the hub wants applied automatically when the glasses come up ("" = none). */
    var hubAutostartDesktop by mutableStateOf("")
    var pairingNeeded by mutableStateOf(false)
    var skipPrivilege by mutableStateOf(true)
    var updateStatus by mutableStateOf("")

    val trackingLabel: String
        get() = when {
            !dofActive -> "brak trackingu"
            glasses?.tracking == TrackingKind.DOF6 -> "6DoF"
            else -> "3DoF"
        }

    fun applyPrivilege(state: PrivilegedService.State) {
        privilegeReady = state == PrivilegedService.State.READY
        pairingNeeded = state == PrivilegedService.State.NEEDS_PAIRING
        setup = when (state) {
            PrivilegedService.State.UNSUPPORTED -> SetupContent(
                "Możesz pominąć to na razie",
                "Parowanie ADB nie jest potrzebne. Pulpit w okularach, touchpad i hub działają bez tego.",
                "Pomiń i używaj okularów",
            )
            PrivilegedService.State.NEEDS_DEVELOPER_OPTIONS -> SetupContent(
                "Możesz pominąć to na razie",
                "To ustawienie jest opcjonalne. Pulpit w okularach działa bez niego.",
                "Pomiń i używaj okularów",
            )
            PrivilegedService.State.NEEDS_WIRELESS_DEBUGGING -> SetupContent(
                "Możesz pominąć to na razie",
                "Debugowanie bezprzewodowe jest opcjonalne. Naciśnij Pomiń i podłącz okulary.",
                "Pomiń i używaj okularów",
            )
            PrivilegedService.State.NEEDS_PAIRING -> SetupContent(
                "Możesz pominąć to na razie",
                "Parowanie jest tylko do odpalania Gmaila/Chrome w okularach. Pulpit, touchpad i hub z komputera działają bez tego. Naciśnij Pomiń.",
                "Pomiń i używaj okularów",
            )
            PrivilegedService.State.DISCOVERING -> SetupContent(
                "Konfiguracja…",
                "Szukam lokalnej usługi ADB…",
            )
            PrivilegedService.State.CONNECTING -> SetupContent(
                "Konfiguracja…",
                "Łączenie z usługą ADB…",
            )
            PrivilegedService.State.STARTING -> SetupContent(
                "Konfiguracja…",
                "Uruchamiam warstwę uprawnień…",
            )
            PrivilegedService.State.READY -> SetupContent(
                "Gotowe",
                "Podłącz okulary VITURE przez USB-C.",
            )
        }
    }
}
