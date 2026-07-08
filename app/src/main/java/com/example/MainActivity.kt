package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import com.example.audio.AudioEngine
import com.example.data.*
import com.example.ui.PadViewModel
import com.example.ui.PadViewModelFactory
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var audioEngine: AudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Setup Room persistence
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "sonicpad_database"
        ).fallbackToDestructiveMigration().build()

        val repository = PadRepository(database.padDao())
        audioEngine = AudioEngine(applicationContext)

        val factory = PadViewModelFactory(repository, audioEngine)
        val viewModel: PadViewModel by viewModels { factory }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1C1B1F) // Sleek Geometric Slate background
                ) {
                    SonicPadScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.release()
    }
}

@Composable
fun SonicPadScreen(viewModel: PadViewModel) {
    val context = LocalContext.current
    val currentBank by viewModel.currentBank.collectAsStateWithLifecycle()
    val fxActive by viewModel.fxActive.collectAsStateWithLifecycle()
    val syncActive by viewModel.syncActive.collectAsStateWithLifecycle()
    val bpmVal by viewModel.bpm.collectAsStateWithLifecycle()
    val activePads by viewModel.activeBankPads.collectAsStateWithLifecycle()
    val playingPads by viewModel.playingPads.collectAsStateWithLifecycle()
    val editingPad by viewModel.editingPad.collectAsStateWithLifecycle()

    // Metronome flashing state synchronizer
    var metronomePulse by remember { mutableStateOf(false) }
    LaunchedEffect(syncActive, bpmVal) {
        if (syncActive) {
            val intervalMs = (60000 / bpmVal).toLong()
            while (true) {
                metronomePulse = true
                delay(120)
                metronomePulse = false
                delay((intervalMs - 120).coerceAtLeast(10))
            }
        } else {
            metronomePulse = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // --- HEADER ---
            HeaderSection(
                currentBank = currentBank,
                fxActive = fxActive,
                syncActive = syncActive,
                onBankSelect = { viewModel.selectBank(it) },
                onFxToggle = { viewModel.toggleFx() },
                onSyncToggle = { viewModel.toggleSync() },
                onStopAll = { viewModel.globalStop() }
            )

            // --- PAD GRID ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (activePads.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4FD8EB))
                    }
                } else {
                    // Arrange 16 pads in a responsive 4x4 layout using Column/Row weights
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (row in 0 until 4) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                for (col in 0 until 4) {
                                    val index = row * 4 + col
                                    if (index < activePads.size) {
                                        val pad = activePads[index]
                                        val isPlaying = playingPads.contains(pad.id)
                                        
                                        PadButton(
                                            pad = pad,
                                            isPlaying = isPlaying,
                                            fxActive = fxActive,
                                            onTap = { viewModel.triggerPad(pad) },
                                            onLongTap = { viewModel.startEditingPad(pad) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- FOOTER CONTROLS ---
            FooterSection(
                bpm = bpmVal,
                metronomePulse = metronomePulse,
                onBpmIncrease = { viewModel.setBpm(bpmVal + 1) },
                onBpmDecrease = { viewModel.setBpm(bpmVal - 1) },
                onTapTempo = { viewModel.tapTempo() }
            )
        }

        // --- CUSTOMIZATION DIALOG ---
        editingPad?.let { pad ->
            PadCustomizerDialog(
                pad = pad,
                onDismiss = { viewModel.stopEditingPad() },
                onSave = { updatedPad ->
                    viewModel.savePadConfig(updatedPad)
                },
                onClearCustom = {
                    viewModel.clearCustomSample(pad)
                },
                onFileSelected = { uri ->
                    viewModel.importCustomAudio(context, uri, pad)
                }
            )
        }
    }
}

@Composable
fun HeaderSection(
    currentBank: String,
    fxActive: Boolean,
    syncActive: Boolean,
    onBankSelect: (String) -> Unit,
    onFxToggle: () -> Unit,
    onSyncToggle: () -> Unit,
    onStopAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    ) {
        // App title & prominent global stop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SonicPad",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5)
                )
                Text(
                    text = "16-PAD CUSTOM SAMPLER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF938F99),
                    letterSpacing = 1.sp
                )
            }

            // Prominent Global Stop button (matching original Geometric Balance design)
            IconButton(
                onClick = onStopAll,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF2B8B5)) // Pinkish red
                    .testTag("global_stop_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop All",
                    tint = Color(0xFF601410),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Row of 4 banks & effects controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderControlBtn(
                text = "BANK A",
                isActive = currentBank == "A",
                onClick = { onBankSelect("A") },
                modifier = Modifier.weight(1f)
            )
            HeaderControlBtn(
                text = "BANK B",
                isActive = currentBank == "B",
                onClick = { onBankSelect("B") },
                modifier = Modifier.weight(1f)
            )
            HeaderControlBtn(
                text = "FX FILTER",
                isActive = fxActive,
                onClick = onFxToggle,
                activeColor = Color(0xFFF97316),
                modifier = Modifier.weight(1f)
            )
            HeaderControlBtn(
                text = "METRONOME",
                isActive = syncActive,
                onClick = onSyncToggle,
                activeColor = Color(0xFFC1FF72),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun HeaderControlBtn(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    activeColor: Color = Color(0xFFD0BCFF),
    modifier: Modifier = Modifier
) {
    val bg = if (isActive) Color(0xFF49454F) else Color(0xFF313033)
    val textCol = if (isActive) activeColor else Color(0xFFCAC4D0)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .testTag("header_btn_$text"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textCol,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PadButton(
    pad: PadConfig,
    isPlaying: Boolean,
    fxActive: Boolean,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ledColorHex = getLedColorHex(pad.ledColor)
    
    // Smooth pulse scaling when playing
    val scale = rememberInfiniteTransition(label = "pulse")
    val animatedScale by scale.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val currentBg = when {
        isPlaying -> Color(0xFF49454F)
        fxActive -> Color(0xFF3B3940) // shift color slightly when FX filter is on
        else -> Color(0xFF313033)
    }

    Box(
        modifier = modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(currentBg)
            .border(
                width = if (isPlaying) 1.dp else 0.dp,
                color = ledColorHex.copy(alpha = 0.8f),
                shape = RoundedCornerShape(16.dp)
            )
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongTap
            )
            .drawBehind {
                // Sleek bottom border LED strip
                val borderWidth = 4.dp.toPx()
                drawRect(
                    color = ledColorHex,
                    topLeft = Offset(0f, size.height - borderWidth),
                    size = Size(size.width, borderWidth)
                )
            }
            .testTag("pad_${pad.id}")
    ) {
        // LED indicator dot at top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (isPlaying) ledColorHex else ledColorHex.copy(alpha = 0.3f)
                )
        )

        // Custom symbol indicator if it uses a custom file sample
        if (!pad.customAudioPath.isNullOrEmpty()) {
            Icon(
                imageVector = Icons.Filled.AudioFile,
                contentDescription = "Custom Sample",
                tint = ledColorHex.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(12.dp)
            )
        }

        // Pad Labels
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 12.dp, start = 8.dp, end = 8.dp, top = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(1.dp))

            // Big, Simple Label
            Text(
                text = pad.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE6E1E5),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            // Playback Mode details
            Text(
                text = getBehaviorLabel(pad.playbackBehavior),
                fontSize = 8.sp,
                color = if (isPlaying) ledColorHex else Color(0xFF938F99),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun FooterSection(
    bpm: Float,
    metronomePulse: Boolean,
    onBpmIncrease: () -> Unit,
    onBpmDecrease: () -> Unit,
    onTapTempo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Color(0xFF2B2930))
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // BPM counter readout
        Column {
            Text(
                text = "TEMPO BPM",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF938F99),
                letterSpacing = 0.5.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%.1f", bpm),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5)
                )
                Spacer(modifier = Modifier.width(12.dp))
                
                // Pulsing visual Metronome indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (metronomePulse) Color(0xFFC1FF72) else Color(0xFF49454F)
                        )
                        .shadow(
                            elevation = if (metronomePulse) 8.dp else 0.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFFC1FF72),
                            spotColor = Color(0xFFC1FF72)
                        )
                )
            }
        }

        // Adjustments & Tap tempo controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Decrease BPM
            IconButton(
                onClick = onBpmDecrease,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF313033))
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Decrease Tempo",
                    tint = Color(0xFFE6E1E5),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Increase BPM
            IconButton(
                onClick = onBpmIncrease,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF313033))
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Increase Tempo",
                    tint = Color(0xFFE6E1E5),
                    modifier = Modifier.size(18.dp)
                )
            }

            // TAP TEMPO button
            Button(
                onClick = onTapTempo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF49454F),
                    contentColor = Color(0xFFE6E1E5)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TAP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PadCustomizerDialog(
    pad: PadConfig,
    onDismiss: () -> Unit,
    onSave: (PadConfig) -> Unit,
    onClearCustom: () -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    var label by remember { mutableStateOf(pad.label) }
    var ledColor by remember { mutableStateOf(pad.ledColor) }
    var soundType by remember { mutableStateOf(pad.soundType) }
    var frequency by remember { mutableStateOf(pad.frequency) }
    var duration by remember { mutableStateOf(pad.durationSeconds) }
    var behavior by remember { mutableStateOf(pad.playbackBehavior) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onFileSelected(uri)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF211F24))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Text(
                    text = "Edit Pad #${pad.id}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5)
                )
                Text(
                    text = "Configure custom triggers, synthesis, and colors",
                    fontSize = 11.sp,
                    color = Color(0xFF938F99),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 1. EDIT LABEL
                Text(
                    text = "Pad Label",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(16) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFFE6E1E5)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = getLedColorHex(ledColor),
                        unfocusedBorderColor = Color(0xFF49454F),
                        focusedLabelColor = getLedColorHex(ledColor),
                        cursorColor = getLedColorHex(ledColor)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_label_field"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2. AUDIO SOURCE IMPORT
                Text(
                    text = "Audio Source",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                
                if (!pad.customAudioPath.isNullOrEmpty()) {
                    // Custom file details
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF313033))
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AudioFile,
                                contentDescription = null,
                                tint = getLedColorHex(ledColor)
                            )
                            Text(
                                text = "Custom Device File loaded",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE6E1E5)
                            )
                        }
                        Text(
                            text = File(pad.customAudioPath).name,
                            fontSize = 9.sp,
                            color = Color(0xFF938F99),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { filePicker.launch("audio/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49454F)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Replace File", fontSize = 10.sp)
                            }
                            Button(
                                onClick = onClearCustom,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF601410)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Revert to Synth", fontSize = 10.sp, color = Color(0xFFF2B8B5))
                            }
                        }
                    }
                } else {
                    // Revert to Wav Synthesis
                    Button(
                        onClick = { filePicker.launch("audio/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49454F)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Custom Audio Sample")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. SYNTHESIS CONTROLS (Only visible if no custom sample loaded)
                if (pad.customAudioPath.isNullOrEmpty()) {
                    Text(
                        text = "Waveform Synthesis Parameters",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6E1E5),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Sound presets select
                    Text(
                        text = "Preset Sound Wave: ${soundType.name}",
                        fontSize = 10.sp,
                        color = Color(0xFF938F99)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val presets = listOf(SoundType.KICK, SoundType.SNARE, SoundType.HIHAT, SoundType.SYNTH_SAW, SoundType.SYNTH_SINE)
                        presets.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (soundType == preset) getLedColorHex(ledColor) else Color(0xFF313033))
                                    .clickable { soundType = preset }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (soundType == preset) Color(0xFF1C1B1F) else Color(0xFFCAC4D0)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Pitch slider
                    Text(
                        text = "Fundamental Frequency (Pitch): ${frequency.toInt()} Hz",
                        fontSize = 10.sp,
                        color = Color(0xFF938F99)
                    )
                    Slider(
                        value = frequency,
                        onValueChange = { frequency = it },
                        valueRange = 40.0f..1500.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = getLedColorHex(ledColor),
                            activeTrackColor = getLedColorHex(ledColor)
                        )
                    )

                    // Duration slider
                    Text(
                        text = "Sample Duration: ${String.format("%.2f", duration)}s",
                        fontSize = 10.sp,
                        color = Color(0xFF938F99)
                    )
                    Slider(
                        value = duration,
                        onValueChange = { duration = it },
                        valueRange = 0.05f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = getLedColorHex(ledColor),
                            activeTrackColor = getLedColorHex(ledColor)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. BEHAVIOR MODE DROPDOWN
                Text(
                    text = "Playback Trigger Behavior",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PlaybackBehavior.values().forEach { mode ->
                        val isSelected = behavior == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) getLedColorHex(ledColor).copy(alpha = 0.15f) else Color.Transparent)
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = getLedColorHex(ledColor),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { behavior = mode }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { behavior = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = getLedColorHex(ledColor))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = getBehaviorModeName(mode),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE6E1E5)
                                )
                                Text(
                                    text = getBehaviorModeDesc(mode),
                                    fontSize = 8.5.sp,
                                    color = Color(0xFF938F99)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. LED COLOR SELECTION
                Text(
                    text = "Pad Glow Color Accent",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6E1E5),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LedColor.values().forEach { col ->
                        val colHex = getLedColorHex(col)
                        val isSelected = ledColor == col
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(colHex)
                                .border(
                                    width = if (isSelected) 3.dp else 0.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .clickable { ledColor = col },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save / Cancel action controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCAC4D0)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val updated = pad.copy(
                                label = label,
                                ledColor = ledColor,
                                soundType = soundType,
                                frequency = frequency,
                                durationSeconds = duration,
                                playbackBehavior = behavior
                            )
                            onSave(updated)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = getLedColorHex(ledColor)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("save_pad_button")
                    ) {
                        Text("Save Changes", color = Color(0xFF1C1B1F), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

fun getLedColorHex(color: LedColor): Color {
    return when (color) {
        LedColor.CYAN -> Color(0xFF4FD8EB)
        LedColor.PINK -> Color(0xFFFF6BD6)
        LedColor.PURPLE -> Color(0xFFD0BCFF)
        LedColor.GREEN -> Color(0xFFC1FF72)
        LedColor.YELLOW -> Color(0xFFFFE04F)
        LedColor.BLUE -> Color(0xFF3B82F6)
        LedColor.ORANGE -> Color(0xFFF97316)
        LedColor.RED -> Color(0xFFF2B8B5)
    }
}

fun getBehaviorLabel(behavior: PlaybackBehavior): String {
    return when (behavior) {
        PlaybackBehavior.ONE_SHOT -> "TRIG"
        PlaybackBehavior.LOOP -> "LOOP"
        PlaybackBehavior.TOGGLE -> "TOGL"
        PlaybackBehavior.PLAY_PAUSE -> "P/P"
        PlaybackBehavior.OVERLAY -> "OVER"
    }
}

fun getBehaviorModeName(behavior: PlaybackBehavior): String {
    return when (behavior) {
        PlaybackBehavior.ONE_SHOT -> "Trigger / One-Shot"
        PlaybackBehavior.LOOP -> "Loop"
        PlaybackBehavior.TOGGLE -> "Toggle Start/Stop"
        PlaybackBehavior.PLAY_PAUSE -> "Play / Pause"
        PlaybackBehavior.OVERLAY -> "Polyphonic Overlay"
    }
}

fun getBehaviorModeDesc(behavior: PlaybackBehavior): String {
    return when (behavior) {
        PlaybackBehavior.ONE_SHOT -> "Starts from the beginning on each press, cutting off prior trigger."
        PlaybackBehavior.LOOP -> "Toggles repeating loop continuous playback on and off."
        PlaybackBehavior.TOGGLE -> "Toggles sound on and off from the beginning."
        PlaybackBehavior.PLAY_PAUSE -> "Toggles play, pauses mid-progress, and resumes seamlessly."
        PlaybackBehavior.OVERLAY -> "Creates overlapping multi-voice voices each time clicked."
    }
}
