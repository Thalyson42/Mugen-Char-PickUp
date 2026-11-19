package com.ikemenselect.viewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ikemenselect.core.Pcm

/** Toca sons PCM curtos (vários ao mesmo tempo). Deve ser chamado da thread principal. */
class SoundPlayer {

    private val active = ArrayList<AudioTrack>()
    private val lastStart = HashMap<Pcm, Long>()

    fun play(pcm: Pcm) {
        if (pcm.data.isEmpty()) return
        // o mesmo som não recomeça em menos de 60 ms: evita pilha de cópias iguais (ruído estourado)
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - (lastStart[pcm] ?: -1000L) < 60) return
        lastStart[pcm] = nowMs
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(if (pcm.bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT)
                        .setSampleRate(pcm.sampleRate)
                        .setChannelMask(if (pcm.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.data.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm.data, 0, pcm.data.size)
            val frames = pcm.data.size / (pcm.channels * pcm.bits / 8)
            track.setNotificationMarkerPosition(frames.coerceAtLeast(1))
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = release(t)
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            // muitos sons ao mesmo tempo: descarta o mais antigo
            if (active.size >= MAX_ACTIVE) release(active.first())
            track.setVolume(0.7f)
            active.add(track)
            track.play()
        } catch (e: Exception) {
            // som é um extra: se o aparelho não conseguir tocar, segue sem som
        }
    }

    fun stopAll() {
        for (t in active.toList()) release(t)
    }

    private fun release(t: AudioTrack) {
        active.remove(t)
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    private companion object {
        const val MAX_ACTIVE = 6
    }
}
