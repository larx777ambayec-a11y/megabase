package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.PadConfig
import com.example.data.PlaybackBehavior
import com.example.data.WavGenerator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AudioEngine(private val context: Context) {
    private val TAG = "AudioEngine"

    // Map of padId -> List of currently active MediaPlayers (for overlay and tracking)
    private val activePlayers = ConcurrentHashMap<Int, MutableList<MediaPlayer>>()
    
    // Map of padId -> MediaPlayer for the paused state in PLAY_PAUSE behavior
    private val pausedPlayers = ConcurrentHashMap<Int, MediaPlayer>()

    // Callback when any pad's play status changes: (padId, isPlaying)
    var onPadPlayStatusChanged: ((Int, Boolean) -> Unit)? = null

    /**
     * Resolves and returns the audio file corresponding to a PadConfig.
     * Generates synthesized waveform audio if no custom sample is assigned.
     */
    fun getAudioFile(padConfig: PadConfig): File {
        if (!padConfig.customAudioPath.isNullOrEmpty()) {
            val customFile = File(padConfig.customAudioPath)
            if (customFile.exists()) {
                return customFile
            }
        }
        
        // Generate synthesized sound unique to the pad parameters
        val fileName = "synth_${padConfig.soundType.name}_${padConfig.frequency.toInt()}_${String.format("%.2f", padConfig.durationSeconds).replace(".", "_")}.wav"
        val file = File(context.cacheDir, fileName)
        if (!file.exists()) {
            Log.d(TAG, "Generating synthesized WAV for pad ${padConfig.id} (${padConfig.soundType.name}) at ${padConfig.frequency}Hz")
            WavGenerator.generateWav(
                soundType = padConfig.soundType,
                frequency = padConfig.frequency,
                duration = padConfig.durationSeconds,
                outputFile = file
            )
        }
        return file
    }

    /**
     * Pre-generates the audio file for a pad to prevent any delay during performance.
     */
    fun preloadPad(padConfig: PadConfig) {
        getAudioFile(padConfig)
    }

    /**
     * Triggers the playback action for a pad depending on its configured behavior.
     */
    fun triggerPad(padConfig: PadConfig) {
        val padId = padConfig.id
        val behavior = padConfig.playbackBehavior
        val file = getAudioFile(padConfig)

        if (!file.exists()) {
            Log.e(TAG, "Audio file not found for pad $padId")
            return
        }

        when (behavior) {
            PlaybackBehavior.ONE_SHOT -> {
                // One-Shot: Stop any currently playing for this pad, start fresh
                stopPadInternal(padId)
                playNewPlayer(padId, file, looping = false)
            }
            PlaybackBehavior.LOOP -> {
                // Loop: Toggle continuous loop playback on and off
                val currentlyLooping = activePlayers[padId]?.any { it.isLooping } == true
                if (currentlyLooping) {
                    stopPadInternal(padId)
                } else {
                    playNewPlayer(padId, file, looping = true)
                }
            }
            PlaybackBehavior.TOGGLE -> {
                // Toggle: Starts from beginning, stops if pressed again
                val currentlyPlaying = activePlayers[padId]?.isNotEmpty() == true
                if (currentlyPlaying) {
                    stopPadInternal(padId)
                } else {
                    playNewPlayer(padId, file, looping = false)
                }
            }
            PlaybackBehavior.PLAY_PAUSE -> {
                // Play/Pause: Start -> Pause -> Resume
                val pausedPlayer = pausedPlayers[padId]
                val playingPlayers = activePlayers[padId]

                if (pausedPlayer != null) {
                    // Resume from paused position
                    pausedPlayers.remove(padId)
                    val players = activePlayers.getOrPut(padId) { mutableListOf() }
                    players.add(pausedPlayer)
                    pausedPlayer.start()
                    onPadPlayStatusChanged?.invoke(padId, true)
                } else if (!playingPlayers.isNullOrEmpty()) {
                    // Pause active player
                    val active = playingPlayers.first()
                    active.pause()
                    playingPlayers.remove(active)
                    pausedPlayers[padId] = active
                    onPadPlayStatusChanged?.invoke(padId, false)
                } else {
                    // Start fresh
                    playNewPlayer(padId, file, looping = false, isPlayPauseMode = true)
                }
            }
            PlaybackBehavior.OVERLAY -> {
                // Overlay: Spawns overlapping instances
                playNewPlayer(padId, file, looping = false, isOverlayMode = true)
            }
        }
    }

    private fun playNewPlayer(padId: Int, file: File, looping: Boolean, isPlayPauseMode: Boolean = false, isOverlayMode: Boolean = false) {
        try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = looping
                prepare()
            }

            val list = activePlayers.getOrPut(padId) { mutableListOf() }
            list.add(mediaPlayer)

            mediaPlayer.setOnCompletionListener { mp ->
                mp.release()
                val activeList = activePlayers[padId]
                activeList?.remove(mp)
                
                // If there are no more active players playing for this pad, notify inactive state
                if (activeList.isNullOrEmpty() && pausedPlayers[padId] == null) {
                    onPadPlayStatusChanged?.invoke(padId, false)
                }
            }

            mediaPlayer.start()
            onPadPlayStatusChanged?.invoke(padId, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio for pad $padId", e)
        }
    }

    /**
     * Checks if a pad is currently playing/active.
     */
    fun isPadPlaying(padId: Int): Boolean {
        val hasActive = activePlayers[padId]?.isNotEmpty() == true
        return hasActive
    }

    /**
     * Stoppage of a specific pad.
     */
    fun stopPad(padId: Int) {
        stopPadInternal(padId)
    }

    private fun stopPadInternal(padId: Int) {
        // Stop active players
        activePlayers[padId]?.forEach { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (e: Exception) {
                // Already released or stopped
            }
        }
        activePlayers.remove(padId)

        // Stop paused players
        pausedPlayers[padId]?.let { player ->
            try {
                player.release()
            } catch (e: Exception) {
                // Already released
            }
        }
        pausedPlayers.remove(padId)

        onPadPlayStatusChanged?.invoke(padId, false)
    }

    /**
     * Global Stop: Stops and releases all players.
     */
    fun stopAll() {
        activePlayers.keys.forEach { padId ->
            stopPadInternal(padId)
        }
        pausedPlayers.keys.forEach { padId ->
            stopPadInternal(padId)
        }
        activePlayers.clear()
        pausedPlayers.clear()
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopAll()
    }
}
