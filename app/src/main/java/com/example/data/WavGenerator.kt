package com.example.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.*

object WavGenerator {
    private const val SAMPLE_RATE = 44100
    private const val BITS_PER_SAMPLE = 16

    fun generateWav(soundType: SoundType, frequency: Float, duration: Float, outputFile: File) {
        val numSamples = (SAMPLE_RATE * duration).toInt()
        val pcmData = ShortArray(numSamples)

        val rand = java.util.Random()

        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE

            val sample = when (soundType) {
                SoundType.KICK -> {
                    // Kick: Frequency drops rapidly from 150Hz to 40Hz
                    val startFreq = 150.0
                    val endFreq = 40.0
                    val freqSweep = endFreq + (startFreq - endFreq) * exp(-t * 25.0)
                    val phase = 2.0 * PI * freqSweep * t
                    val ampEnvelope = exp(-t * 15.0)
                    val value = sin(phase) * ampEnvelope
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.SNARE -> {
                    // Snare: Noise + Sine wave around 180Hz
                    val sineFreq = 180.0
                    val phase = 2.0 * PI * sineFreq * t
                    val sineVal = sin(phase) * exp(-t * 20.0) * 0.4
                    
                    val noiseVal = (rand.nextDouble() * 2.0 - 1.0) * exp(-t * 12.0) * 0.6
                    
                    val value = sineVal + noiseVal
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.HIHAT -> {
                    // Hi-Hat: Short, high-pass filtered white noise
                    val noiseVal = (rand.nextDouble() * 2.0 - 1.0)
                    // High pass filter emulation (difference from previous)
                    val ampEnvelope = exp(-t * 35.0)
                    val value = noiseVal * ampEnvelope * 0.7
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.TOM -> {
                    // Tom: Pitch sweeps from 120Hz to 55Hz, slower decay than Kick
                    val startFreq = 120.0
                    val endFreq = 55.0
                    val freqSweep = endFreq + (startFreq - endFreq) * exp(-t * 10.0)
                    val phase = 2.0 * PI * freqSweep * t
                    val ampEnvelope = exp(-t * 6.0)
                    val value = sin(phase) * ampEnvelope
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.CLAP -> {
                    // Clap: Multi-burst noise (e.g. 3 bursts 15ms apart, then tail)
                    val burstInterval = 0.015
                    val burstAmp = when {
                        t < burstInterval -> exp(-t * 120.0)
                        t < burstInterval * 2 -> exp(-(t - burstInterval) * 120.0)
                        t < burstInterval * 3 -> exp(-(t - burstInterval * 2) * 120.0)
                        else -> exp(-(t - burstInterval * 3) * 15.0)
                    }
                    val noiseVal = (rand.nextDouble() * 2.0 - 1.0) * burstAmp * 0.7
                    (noiseVal * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.SYNTH_SINE -> {
                    // Synth Sine: Sine wave at configured frequency with Attack-Release
                    val phase = 2.0 * PI * frequency.toDouble() * t
                    // Attack (20ms) and Release (100ms)
                    val attackTime = 0.02
                    val releaseTime = 0.1
                    val ampEnvelope = when {
                        t < attackTime -> t / attackTime
                        t > (duration - releaseTime) -> (duration - t) / releaseTime
                        else -> 1.0
                    }
                    val value = sin(phase) * ampEnvelope * 0.8
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.SYNTH_SAW -> {
                    // Synth Saw: Sawtooth wave with Attack-Release
                    val period = 1.0 / frequency.toDouble()
                    val phase = (t % period) / period
                    val sawVal = (phase * 2.0 - 1.0)
                    
                    val attackTime = 0.02
                    val releaseTime = 0.1
                    val ampEnvelope = when {
                        t < attackTime -> t / attackTime
                        t > (duration - releaseTime) -> (duration - t) / releaseTime
                        else -> 1.0
                    }
                    val value = sawVal * ampEnvelope * 0.6
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.AMBIENT_DRONE -> {
                    // Ambient Drone: Chord (Freq, Freq*1.5, Freq*2.0) with very slow attack
                    val f = frequency.toDouble()
                    val val1 = sin(2.0 * PI * f * t)
                    val val2 = sin(2.0 * PI * (f * 1.5) * t) * 0.6
                    val val3 = sin(2.0 * PI * (f * 2.0) * t) * 0.4
                    
                    val attackTime = 0.4
                    val releaseTime = 0.4
                    val ampEnvelope = when {
                        t < attackTime -> t / attackTime
                        t > (duration - releaseTime) -> (duration - t) / releaseTime
                        else -> 1.0
                    }
                    val value = (val1 + val2 + val3) / 2.0 * ampEnvelope * 0.7
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.LASER_SWEEP -> {
                    // Laser Sweep: Exponential frequency sweep downwards
                    val startFreq = 2000.0
                    val endFreq = 200.0
                    val freqSweep = endFreq + (startFreq - endFreq) * exp(-t * 8.0)
                    val phase = 2.0 * PI * freqSweep * t
                    val ampEnvelope = exp(-t * 5.0)
                    val value = sin(phase) * ampEnvelope
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
                SoundType.SPACE_PLUCK -> {
                    // Space Pluck: Sawtooth wave with rapid filter sweep (sine-like tail)
                    val f = frequency.toDouble()
                    val period = 1.0 / f
                    val sawPhase = (t % period) / period
                    val sawVal = (sawPhase * 2.0 - 1.0)
                    
                    val filterSweep = exp(-t * 20.0)
                    val cleanSine = sin(2.0 * PI * f * t)
                    val value = (sawVal * filterSweep + cleanSine * (1.0 - filterSweep)) * exp(-t * 8.0) * 0.7
                    (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            pcmData[i] = sample
        }

        try {
            writeWavFile(outputFile, pcmData)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun writeWavFile(file: File, pcmData: ShortArray) {
        val totalAudioLen = pcmData.size * 2
        val totalDataLen = totalAudioLen + 36
        val sampleRate = SAMPLE_RATE
        val channels = 1
        val byteRate = SAMPLE_RATE * 2

        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte() // RIFF
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte() // WAVE
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte() // fmt 
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // Subchunk1Size (16 for PCM)
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // AudioFormat (1 for PCM)
            header[21] = 0
            header[22] = channels.toByte() // NumChannels (1)
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2 // BlockAlign (channels * bitsPerSample / 8 = 2)
            header[33] = 0
            header[34] = BITS_PER_SAMPLE.toByte() // BitsPerSample (16)
            header[35] = 0
            header[36] = 'd'.code.toByte() // data
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            out.write(header, 0, 44)

            // Convert shorts to little-endian bytes
            val byteBuffer = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                val value = pcmData[i].toInt()
                byteBuffer[i * 2] = (value and 0xff).toByte()
                byteBuffer[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
            }
            out.write(byteBuffer)
        }
    }
}
