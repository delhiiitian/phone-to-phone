package com.hotspotmirror.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Building block for the call-audio bridge: captures the mic as 20 ms PCM16 frames
 * and plays received frames. Both phones run one. Needs RECORD_AUDIO granted before [startCapture].
 */
class AudioRelay(
    private val captureSource: Int = MediaRecorder.AudioSource.MIC,
    private val playbackUsage: Int = AudioAttributes.USAGE_MEDIA,
) {
    @Volatile private var capturing = false
    private var track: AudioTrack? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun startCapture(onFrame: (ByteArray) -> Unit) {
        if (capturing) return
        capturing = true
        thread(name = "audio-capture") {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(captureSource, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME_BYTES * 4))
            try {
                rec.startRecording()
                val buf = ByteArray(FRAME_BYTES)
                while (capturing) {
                    var off = 0
                    while (off < FRAME_BYTES && capturing) {
                        val n = rec.read(buf, off, FRAME_BYTES - off)
                        if (n <= 0) return@thread
                        off += n
                    }
                    if (off == FRAME_BYTES) onFrame(buf.copyOf())
                }
            } finally {
                runCatching { rec.stop() }; rec.release()
            }
        }
    }

    @Synchronized
    fun play(pcm: ByteArray) {
        val t = track ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(playbackUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(FRAME_BYTES * 8)
            .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play(); track = it }
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun stop() {
        capturing = false
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    companion object {
        const val RATE = 16_000
        const val FRAME_BYTES = RATE / 50 * 2 // 20 ms of mono PCM16
    }
}
