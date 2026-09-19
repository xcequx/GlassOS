package com.uxspace.phone

import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import android.view.TextureView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.uxspace.apps.InstalledApp
import com.uxspace.input.TrackpadView
import com.uxspace.phone.theme.GlassAccent
import com.uxspace.phone.theme.GlassBg
import com.uxspace.phone.theme.GlassDanger
import com.uxspace.phone.theme.GlassMuted
import com.uxspace.phone.theme.GlassOk
import com.uxspace.phone.theme.GlassOsTheme
import com.uxspace.phone.theme.GlassSurface
import com.uxspace.phone.theme.GlassSurfaceHi
import com.uxspace.phone.theme.GlassText


data class PhoneActions(
    val onSetupAction: () -> Unit,
    val onRecenter: () -> Unit,
    val onToggleHeadCursor: () -> Unit,
    val onToggleViewMode: () -> Unit,
    val onReconnect: () -> Unit,
    val onApplyWorkspace: (WorkspacePreset) -> Unit,
    val onLaunchApp: (InstalledApp) -> Unit,
    val onBrightness: (Int) -> Unit,
    val onFilm: (Float) -> Unit,
    val onToggle3d: () -> Unit,
    val onKeyboard: () -> Unit,
    val onHome: () -> Unit,
    val onImeText: (String) -> Unit,
    val bindTrackpad: (TrackpadView) -> Unit,
    val onGatewayUrl: (String) -> Unit,
    val onTestGateway: () -> Unit,
    val onSelectCamera: (String) -> Unit,
    val onStartCamera: (TextureView) -> Unit,
    val onStopCamera: () -> Unit,
    val onAsk: (String) -> Unit,
    val onVoice: () -> Unit,
    val onToggleLive: () -> Unit,
    val onApplyHubDesktop: (String) -> Unit,
    val onPair: (code: String, host: String, port: String) -> Unit,
    val onCheckUpdate: () -> Unit,
    val onSkipSetup: () -> Unit,
    val onZoomBy: (Float) -> Unit,
    val onPadLeft: () -> Unit,
    val onPadRight: () -> Unit,
    val onPadScroll: (Float) -> Unit,
    val onNextScreen: () -> Unit,
)

@Composable
fun GlassOsApp(state: PhonePanelState, actions: PhoneActions) {
    GlassOsTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(GlassBg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            when (state.scene) {
                PanelScene.SETUP -> SetupScene(state, actions)
                PanelScene.WAITING -> WaitingScene()
                PanelScene.CONTROL -> ControlScene(state, actions)
            }
            if (state.keyboardVisible) {
                HiddenIme(onText = actions.onImeText)
            }
        }
    }
}

@Composable
private fun SetupScene(state: PhonePanelState, actions: PhoneActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GLASSOS", color = GlassAccent, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(state.setup.title, color = GlassText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(state.setup.message, color = GlassMuted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = actions.onSkipSetup,
            colors = ButtonDefaults.buttonColors(containerColor = GlassAccent, contentColor = Color(0xFF00333C)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(state.setup.actionLabel ?: "Pomiń i używaj okularów", fontWeight = FontWeight.SemiBold) }
        if (state.pairingNeeded) {
            var code by remember { mutableStateOf("") }
            var host by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("") }
            Spacer(Modifier.height(20.dp))
            Text(
                "Opcjonalne — tylko jeśli chcesz Gmaila lub Chrome na ekranach okularów. Pulpit i touchpad działają bez tego.",
                color = GlassMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-cyfrowy kod", color = GlassMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GlassText,
                    unfocusedTextColor = GlassText,
                    focusedBorderColor = GlassAccent,
                    unfocusedBorderColor = Color(0xFF2A3344),
                ),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("IP z okna parowania (np. 192.168.1.20)", color = GlassMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GlassText,
                    unfocusedTextColor = GlassText,
                    focusedBorderColor = GlassAccent,
                    unfocusedBorderColor = Color(0xFF2A3344),
                ),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port z okna parowania (nie 30100)", color = GlassMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = GlassText,
                    unfocusedTextColor = GlassText,
                    focusedBorderColor = GlassAccent,
                    unfocusedBorderColor = Color(0xFF2A3344),
                ),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { actions.onPair(code, host, port) },
                enabled = code.length == 6,
                colors = ButtonDefaults.buttonColors(containerColor = GlassAccent, contentColor = Color(0xFF00333C)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sparuj (opcjonalnie)", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun WaitingScene() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("GLASSOS", color = GlassAccent, fontSize = 13.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Podłącz okulary", color = GlassText, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Włóż kabel USB-C do VITURE Luma. Telefon zostanie trackpadem, a okulary osobnym pulpitem.",
            color = GlassMuted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun ControlScene(state: PhonePanelState, actions: PhoneActions) {
    Column(Modifier.fillMaxSize()) {
        Dashboard(state, actions)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (state.tab) {
                ControlTab.TOUCHPAD -> TouchpadPane(state, actions)
                ControlTab.APPS -> AppsPane(state, actions)
                ControlTab.WORKSPACE -> WorkspacePane(state, actions)
                ControlTab.AI -> AiPane(state, actions)
            }
        }
        QuickBar(state, actions)
        BottomNav(state)
    }
}

@Composable
private fun Dashboard(state: PhonePanelState, actions: PhoneActions) {
    val g = state.glasses
    val glassesOn = g?.connected == true
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("GLASSOS", color = GlassAccent, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        LinkRow("Okulary", if (glassesOn) g?.modelName ?: "podłączone" else "niepodłączone — USB-C", glassesOn)
        LinkRow("Komputer", state.hubStatus.ifBlank { "szukam huba…" }, state.hubConnected)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.aiGatewayUrl,
            onValueChange = actions.onGatewayUrl,
            label = { Text("Adres komputera", color = GlassMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GlassText,
                unfocusedTextColor = GlassText,
                focusedBorderColor = GlassAccent,
                unfocusedBorderColor = Color(0xFF2A3344),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickToggle("Test PC", false, true, actions.onTestGateway)
            QuickToggle("Aktualizuj", false, true, actions.onCheckUpdate)
            QuickToggle(if (state.aiBusy) "…" else "Głos", false, true, actions.onVoice)
        }
        if (state.statusLine.isNotBlank()) {
            Text(state.statusLine, color = GlassAccent, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun LinkRow(label: String, value: String, on: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(on)
        Spacer(Modifier.width(8.dp))
        Text("$label:", color = GlassMuted, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = if (on) GlassOk else GlassText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (on) GlassOk else GlassDanger),
    )
}

@Composable
private fun TouchpadPane(state: PhonePanelState, actions: PhoneActions) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(GlassSurface)
                .border(1.dp, Color(0xFF2A3344), RoundedCornerShape(22.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    TrackpadView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        actions.bindTrackpad(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "PAD",
                color = GlassMuted.copy(alpha = 0.45f),
                fontSize = 12.sp,
                letterSpacing = 3.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            )
            Text(
                "zoom ${state.zoomPercent}%",
                color = GlassAccent,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PadButton("L", Modifier.weight(1f), actions.onPadLeft)
            PadButton("P", Modifier.weight(1f), actions.onPadRight)
            PadButton("▲", Modifier.weight(1f)) { actions.onPadScroll(-0.22f) }
            PadButton("▼", Modifier.weight(1f)) { actions.onPadScroll(0.22f) }
            PadButton("−", Modifier.weight(0.9f)) { actions.onZoomBy(0.85f) }
            PadButton("+", Modifier.weight(0.9f)) { actions.onZoomBy(1.18f) }
            PadButton("Ekran", Modifier.weight(1.2f), actions.onNextScreen)
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurfaceHi)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = GlassText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun AppsPane(state: PhonePanelState, actions: PhoneActions) {
    val pins = remember(state.apps) { QuickLauncher.resolve(state.apps) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Szybki start", color = GlassMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            pins.forEach { (pin, app) ->
                PinChip(pin.label) { actions.onLaunchApp(app) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Wszystkie aplikacje", color = GlassMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.apps, key = { it.packageName + it.activityName }) { app ->
                AppCell(app) { actions.onLaunchApp(app) }
            }
        }
    }
}

@Composable
private fun PinChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(GlassSurfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = GlassText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AppCell(app: InstalledApp, onClick: () -> Unit) {
    val painter = remember(app.packageName) {
        runCatching { BitmapPainter(app.icon.toBitmap(96, 96).asImageBitmap()) }.getOrNull()
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = app.label,
                modifier = Modifier.size(44.dp),
            )
        } else {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlassSurfaceHi),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            app.label,
            color = GlassText,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WorkspacePane(state: PhonePanelState, actions: PhoneActions) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Text("Z komputera", color = GlassMuted, fontSize = 12.sp)
            Text(
                state.hubStatus,
                color = if (state.hubConnected) GlassOk else GlassMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        items(state.hubDesktops, key = { it.id }) { desk ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassSurface)
                    .clickable { actions.onApplyHubDesktop(desk.id) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(desk.name, color = GlassText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "${desk.layout} · ${desk.screens.sumOf { it.size }} aplikacji",
                        color = GlassMuted,
                        fontSize = 12.sp,
                    )
                }
                Text("włącz", color = GlassAccent, fontSize = 12.sp)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Układy na telefonie", color = GlassMuted, fontSize = 12.sp)
        }
        items(WorkspacePreset.entries) { preset ->
            val selected = when (preset) {
                WorkspacePreset.FOCUS -> state.viewModePinned
                WorkspacePreset.ONE -> !state.viewModePinned && state.layout.name == "SINGLE"
                WorkspacePreset.TWO -> state.layout.name == "TWO_SBS"
                WorkspacePreset.THREE -> state.layout.name.startsWith("THREE")
                WorkspacePreset.CINEMA -> state.layout.name == "SINGLE_WIDE"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) GlassAccent.copy(alpha = 0.16f) else GlassSurface)
                    .border(
                        1.dp,
                        if (selected) GlassAccent else Color(0xFF2A3344),
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { actions.onApplyWorkspace(preset) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(preset.label, color = GlassText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(preset.hint, color = GlassMuted, fontSize = 12.sp)
                }
                if (selected) Text("aktywny", color = GlassAccent, fontSize = 11.sp)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.viewModePinned) "Tryb PINNED — pulpit jedzie z głową."
                else "Tryb FREE — pulpit stoi w przestrzeni, kręcisz głową.",
                color = GlassMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun QuickBar(state: PhonePanelState, actions: PhoneActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(GlassSurface)
            .padding(12.dp),
    ) {
        if (state.brightness >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jasność", color = GlassMuted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                Slider(
                    value = state.brightness.toFloat(),
                    onValueChange = { actions.onBrightness(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = GlassAccent, activeTrackColor = GlassAccent),
                )
                Text("${(state.brightness * 12.5f).toInt()}%", color = GlassText, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Przyciemn.", color = GlassMuted, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                Slider(
                    value = state.filmPercent,
                    onValueChange = { actions.onFilm(it) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = GlassAccent, activeTrackColor = GlassAccent),
                )
                Text("${state.filmPercent.toInt()}%", color = GlassText, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickToggle("3DoF", !state.viewModePinned, enabled = true, onClick = actions.onToggleViewMode)
            QuickToggle("Head", state.headCursor, enabled = true, onClick = actions.onToggleHeadCursor)
            QuickToggle("Recenter", false, enabled = true, onClick = actions.onRecenter)
            QuickToggle(if (state.stereo3d) "3D" else "2D", state.stereo3d, enabled = true, onClick = actions.onToggle3d)
            QuickToggle("Klaw.", state.keyboardVisible, enabled = true, onClick = actions.onKeyboard)
        }
    }
}

@Composable
private fun QuickToggle(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = when {
        !enabled -> GlassSurfaceHi.copy(alpha = 0.5f)
        on -> GlassAccent.copy(alpha = 0.22f)
        else -> GlassSurfaceHi
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (on) GlassAccent else GlassText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BottomNav(state: PhonePanelState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NavItem("Aplikacje", state.tab == ControlTab.APPS, Modifier.weight(1f)) { state.tab = ControlTab.APPS }
        NavItem("Pad", state.tab == ControlTab.TOUCHPAD, Modifier.weight(1f)) { state.tab = ControlTab.TOUCHPAD }
        NavItem("Ekrany", state.tab == ControlTab.WORKSPACE, Modifier.weight(1f)) { state.tab = ControlTab.WORKSPACE }
        NavItem("AI", state.tab == ControlTab.AI, Modifier.weight(1f)) { state.tab = ControlTab.AI }
    }
}

@Composable
private fun AiPane(state: PhonePanelState, actions: PhoneActions) {
    var prompt by remember { mutableStateOf("Co widzę? Opisz krótko.") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(GlassSurface)
                .border(1.dp, Color(0xFF2A3344), RoundedCornerShape(18.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { view ->
                        actions.onStartCamera(view)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose { actions.onStopCamera() }
            }
            Text(
                if (state.cameraStreaming) state.cameraSource else "kamera wyłączona",
                color = GlassMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(state.cameraLabel, color = GlassMuted, fontSize = 11.sp)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.cameraSources.take(4).forEach { name ->
                val on = name == state.selectedCamera
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) GlassAccent.copy(alpha = 0.22f) else GlassSurfaceHi)
                        .clickable { actions.onSelectCamera(name) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(name, color = if (on) GlassAccent else GlassText, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.aiGatewayUrl,
            onValueChange = actions.onGatewayUrl,
            label = { Text("Hub / PC", color = GlassMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GlassText,
                unfocusedTextColor = GlassText,
                focusedBorderColor = GlassAccent,
                unfocusedBorderColor = Color(0xFF2A3344),
            ),
        )
        Text(state.aiGatewayStatus, color = GlassMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        if (state.updateStatus.isNotBlank()) {
            Text(state.updateStatus, color = GlassAccent, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickToggle("Test PC", false, true, actions.onTestGateway)
            QuickToggle("Live", state.aiLive, true, actions.onToggleLive)
            QuickToggle("Aktualizuj", false, true, actions.onCheckUpdate)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Pytanie", color = GlassMuted) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = GlassText,
                unfocusedTextColor = GlassText,
                focusedBorderColor = GlassAccent,
                unfocusedBorderColor = Color(0xFF2A3344),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onAsk(prompt.ifBlank { "Co widzę?" }) },
                enabled = !state.aiBusy,
                colors = ButtonDefaults.buttonColors(containerColor = GlassAccent, contentColor = Color(0xFF00333C)),
                modifier = Modifier.weight(1f),
            ) { Text(if (state.aiBusy) "czekam…" else "Pytaj") }
            Button(
                onClick = actions.onVoice,
                enabled = !state.aiBusy,
                colors = ButtonDefaults.buttonColors(containerColor = GlassSurfaceHi, contentColor = GlassText),
                modifier = Modifier.weight(1f),
            ) { Text("Głos") }
            Button(
                onClick = { actions.onAsk("To jakieś urządzenie albo ekran? Odczytaj napisy i powiedz co robić.") },
                enabled = !state.aiBusy,
                colors = ButtonDefaults.buttonColors(containerColor = GlassSurfaceHi, contentColor = GlassText),
                modifier = Modifier.weight(1f),
            ) { Text("Odczytaj") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "klatki wysłane: ${state.aiFramesSent}",
            color = GlassMuted,
            fontSize = 11.sp,
        )
        if (state.aiAnswer.isNotBlank()) {
            Text(
                state.aiAnswer,
                color = GlassText,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassSurface)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) GlassAccent else GlassSurface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF00333C) else GlassText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun HiddenIme(onText: (String) -> Unit) {
    BasicTextField(
        value = "",
        onValueChange = onText,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp, fontFamily = FontFamily.Default),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.size(1.dp),
    )
}


