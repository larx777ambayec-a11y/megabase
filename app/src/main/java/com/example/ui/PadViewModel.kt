package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngine
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class PadViewModel(
    private val repository: PadRepository,
    private val audioEngine: AudioEngine
) : ViewModel() {

    private val TAG = "PadViewModel"

    // Set of currently active/playing pad IDs
    private val _playingPads = MutableStateFlow<Set<Int>>(emptySet())
    val playingPads: StateFlow<Set<Int>> = _playingPads.asStateFlow()

    // Current bank selection: "A" or "B"
    private val _currentBank = MutableStateFlow("A")
    val currentBank: StateFlow<String> = _currentBank.asStateFlow()

    // FX toggle state for visual fun & sound effects UI
    private val _fxActive = MutableStateFlow(false)
    val fxActive: StateFlow<Boolean> = _fxActive.asStateFlow()

    // Sync toggle state for visual bpm synchronization
    private val _syncActive = MutableStateFlow(false)
    val syncActive: StateFlow<Boolean> = _syncActive.asStateFlow()

    // Current BPM tempo control
    private val _bpm = MutableStateFlow(124.0f)
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    // Selected Pad Config for Editing dialog (null if closed)
    private val _editingPad = MutableStateFlow<PadConfig?>(null)
    val editingPad: StateFlow<PadConfig?> = _editingPad.asStateFlow()

    // List of timestamps of recent BPM taps
    private val tapTimestamps = mutableListOf<Long>()

    // All pad configs from the database
    val allPads: StateFlow<List<PadConfig>> = repository.allPads
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current active 16 pads filtered by bank selection
    val activeBankPads: StateFlow<List<PadConfig>> = combine(allPads, currentBank) { pads, bank ->
        val range = if (bank == "A") 1..16 else 17..32
        val bankPads = pads.filter { it.id in range }
        
        // If the bank is not seeded yet, seed default configs
        if (pads.isEmpty() || bankPads.size < 16) {
            emptyList()
        } else {
            bankPads
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        // Handle audio engine playback callbacks to update active glowing pads
        audioEngine.onPadPlayStatusChanged = { padId, isPlaying ->
            viewModelScope.launch {
                val current = _playingPads.value.toMutableSet()
                if (isPlaying) {
                    current.add(padId)
                } else {
                    current.remove(padId)
                }
                _playingPads.value = current
            }
        }

        // Lazy seed default 32 pads if empty
        viewModelScope.launch(Dispatchers.IO) {
            repository.allPads.first().let { existingPads ->
                if (existingPads.size < 32) {
                    Log.d(TAG, "Seeding default pad configurations in Room database")
                    val defaults = createDefaultPadConfigs()
                    repository.insertInitialPads(defaults)
                    
                    // Preload audio files
                    defaults.forEach { pad ->
                        audioEngine.preloadPad(pad)
                    }
                }
            }
        }
    }

    /**
     * Toggles Bank selector.
     */
    fun selectBank(bank: String) {
        if (bank == "A" || bank == "B") {
            _currentBank.value = bank
        }
    }

    /**
     * Toggles sound effect filter state.
     */
    fun toggleFx() {
        _fxActive.value = !_fxActive.value
    }

    /**
     * Toggles Sync/Metronome display.
     */
    fun toggleSync() {
        _syncActive.value = !_syncActive.value
    }

    /**
     * Global Stop: stop all sounds immediately.
     */
    fun globalStop() {
        audioEngine.stopAll()
        _playingPads.value = emptySet()
    }

    /**
     * Trigger play for a pad.
     */
    fun triggerPad(pad: PadConfig) {
        audioEngine.triggerPad(pad)
    }

    /**
     * Open pad edit dialog.
     */
    fun startEditingPad(pad: PadConfig) {
        _editingPad.value = pad
    }

    /**
     * Close pad edit dialog.
     */
    fun stopEditingPad() {
        _editingPad.value = null
    }

    /**
     * Save updated pad settings.
     */
    fun savePadConfig(pad: PadConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePad(pad)
            audioEngine.preloadPad(pad)
            if (_editingPad.value?.id == pad.id) {
                _editingPad.value = pad
            }
        }
    }

    /**
     * Clears custom sample and returns pad back to default synthesis sound.
     */
    fun clearCustomSample(pad: PadConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = pad.copy(customAudioPath = null)
            repository.updatePad(updated)
            audioEngine.preloadPad(updated)
            if (_editingPad.value?.id == pad.id) {
                _editingPad.value = updated
            }
        }
    }

    /**
     * Set BPM tempo.
     */
    fun setBpm(newBpm: Float) {
        _bpm.value = newBpm.coerceIn(40.0f, 240.0f)
    }

    /**
     * Tap Tempo logic: Tap to set BPM based on interval average.
     */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        tapTimestamps.add(now)

        // Only keep taps within the last 2 seconds
        tapTimestamps.removeAll { now - it > 2000 }

        if (tapTimestamps.size >= 2) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until tapTimestamps.size) {
                intervals.add(tapTimestamps[i] - tapTimestamps[i - 1])
            }
            val averageIntervalMs = intervals.average()
            if (averageIntervalMs > 0) {
                val calculatedBpm = 60000.0f / averageIntervalMs.toFloat()
                setBpm(calculatedBpm)
            }
        }
    }

    /**
     * Import custom sample Uri from device storage by copying to the app's local directory.
     */
    fun importCustomAudio(context: Context, uri: Uri, pad: PadConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val extension = getFileExtensionFromUri(context, uri) ?: "wav"
                val fileName = "custom_pad_${pad.id}_${System.currentTimeMillis()}.$extension"
                val destFile = File(context.filesDir, fileName)

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (destFile.exists() && destFile.length() > 0) {
                    val originalName = getFileNameFromUri(context, uri) ?: "PAD ${pad.id} Custom"
                    val updated = pad.copy(
                        customAudioPath = destFile.absolutePath,
                        label = originalName.take(12) // Limit label length
                    )
                    repository.updatePad(updated)
                    audioEngine.preloadPad(updated)
                    
                    // Update editing state to refresh dialog if open
                    if (_editingPad.value?.id == pad.id) {
                        _editingPad.value = updated
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import custom audio for pad ${pad.id}", e)
            }
        }
    }

    private fun getFileExtensionFromUri(context: Context, uri: Uri): String? {
        val type = context.contentResolver.getType(uri)
        if (type != null) {
            val parts = type.split("/")
            if (parts.size > 1) {
                return parts[1]
            }
        }
        return uri.path?.substringAfterLast('.', "wav")
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result?.substringBeforeLast('.')
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }

    /**
     * Create the default 32 pad configurations (Bank A: 1-16, Bank B: 17-32)
     */
    private fun createDefaultPadConfigs(): List<PadConfig> {
        return listOf(
            // Bank A (Pads 1-16)
            PadConfig(1, "KICK 01", SoundType.KICK, 60.0f, 0.3f, PlaybackBehavior.ONE_SHOT, LedColor.CYAN),
            PadConfig(2, "KICK 02", SoundType.KICK, 50.0f, 0.4f, PlaybackBehavior.ONE_SHOT, LedColor.CYAN),
            PadConfig(3, "SNARE 1", SoundType.SNARE, 180.0f, 0.25f, PlaybackBehavior.ONE_SHOT, LedColor.PINK),
            PadConfig(4, "CLAP", SoundType.CLAP, 220.0f, 0.28f, PlaybackBehavior.ONE_SHOT, LedColor.PINK),
            PadConfig(5, "HH CL", SoundType.HIHAT, 8000.0f, 0.08f, PlaybackBehavior.ONE_SHOT, LedColor.GREEN),
            PadConfig(6, "HH OP", SoundType.HIHAT, 8000.0f, 0.45f, PlaybackBehavior.LOOP, LedColor.GREEN),
            PadConfig(7, "TOM 01", SoundType.TOM, 120.0f, 0.4f, PlaybackBehavior.OVERLAY, LedColor.PURPLE),
            PadConfig(8, "TOM 02", SoundType.TOM, 90.0f, 0.45f, PlaybackBehavior.OVERLAY, LedColor.PURPLE),
            PadConfig(9, "VOX 01", SoundType.SYNTH_SINE, 440.0f, 0.8f, PlaybackBehavior.LOOP, LedColor.YELLOW),
            PadConfig(10, "VOX 02", SoundType.SYNTH_SAW, 554.37f, 0.8f, PlaybackBehavior.LOOP, LedColor.YELLOW),
            PadConfig(11, "CRASH", SoundType.LASER_SWEEP, 1000.0f, 0.6f, PlaybackBehavior.ONE_SHOT, LedColor.PURPLE),
            PadConfig(12, "RIDE", SoundType.SPACE_PLUCK, 659.25f, 0.5f, PlaybackBehavior.ONE_SHOT, LedColor.PURPLE),
            PadConfig(13, "SYNTH L", SoundType.AMBIENT_DRONE, 130.81f, 1.5f, PlaybackBehavior.PLAY_PAUSE, LedColor.PINK),
            PadConfig(14, "SYNTH H", SoundType.AMBIENT_DRONE, 261.63f, 1.5f, PlaybackBehavior.PLAY_PAUSE, LedColor.PINK),
            PadConfig(15, "BASS 01", SoundType.SYNTH_SAW, 65.41f, 0.6f, PlaybackBehavior.TOGGLE, LedColor.CYAN),
            PadConfig(16, "BASS 02", SoundType.SYNTH_SINE, 82.41f, 0.6f, PlaybackBehavior.TOGGLE, LedColor.GREEN),

            // Bank B (Pads 17-32)
            PadConfig(17, "SUB KICK", SoundType.KICK, 45.0f, 0.45f, PlaybackBehavior.ONE_SHOT, LedColor.CYAN),
            PadConfig(18, "LATE KICK", SoundType.KICK, 55.0f, 0.25f, PlaybackBehavior.ONE_SHOT, LedColor.CYAN),
            PadConfig(19, "NOISE SNR", SoundType.SNARE, 150.0f, 0.3f, PlaybackBehavior.ONE_SHOT, LedColor.PINK),
            PadConfig(20, "SYNTH CLP", SoundType.CLAP, 300.0f, 0.25f, PlaybackBehavior.ONE_SHOT, LedColor.PINK),
            PadConfig(21, "METAL HH", SoundType.HIHAT, 10000.0f, 0.06f, PlaybackBehavior.ONE_SHOT, LedColor.GREEN),
            PadConfig(22, "SHAKER", SoundType.HIHAT, 6000.0f, 0.18f, PlaybackBehavior.ONE_SHOT, LedColor.GREEN),
            PadConfig(23, "HI TOM", SoundType.TOM, 160.0f, 0.35f, PlaybackBehavior.OVERLAY, LedColor.PURPLE),
            PadConfig(24, "MID TOM", SoundType.TOM, 140.0f, 0.35f, PlaybackBehavior.OVERLAY, LedColor.PURPLE),
            PadConfig(25, "LEAD A", SoundType.SYNTH_SAW, 329.63f, 0.8f, PlaybackBehavior.OVERLAY, LedColor.YELLOW),
            PadConfig(26, "LEAD B", SoundType.SYNTH_SAW, 392.00f, 0.8f, PlaybackBehavior.OVERLAY, LedColor.YELLOW),
            PadConfig(27, "LASER HI", SoundType.LASER_SWEEP, 3000.0f, 0.5f, PlaybackBehavior.ONE_SHOT, LedColor.PURPLE),
            PadConfig(28, "PLUCK C", SoundType.SPACE_PLUCK, 523.25f, 0.5f, PlaybackBehavior.ONE_SHOT, LedColor.PURPLE),
            PadConfig(29, "CHORD 1", SoundType.AMBIENT_DRONE, 196.00f, 2.0f, PlaybackBehavior.LOOP, LedColor.PINK),
            PadConfig(30, "CHORD 2", SoundType.AMBIENT_DRONE, 220.00f, 2.0f, PlaybackBehavior.LOOP, LedColor.PINK),
            PadConfig(31, "BASS C", SoundType.SYNTH_SAW, 65.41f, 0.8f, PlaybackBehavior.TOGGLE, LedColor.CYAN),
            PadConfig(32, "BASS G", SoundType.SYNTH_SAW, 98.00f, 0.8f, PlaybackBehavior.TOGGLE, LedColor.GREEN)
        )
    }
}

class PadViewModelFactory(
    private val repository: PadRepository,
    private val audioEngine: AudioEngine
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PadViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PadViewModel(repository, audioEngine) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
