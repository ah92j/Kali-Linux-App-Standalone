package com.orailnoor.droiddesk.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Routes PulseAudio PCM output to Android's native AudioTrack.
 *
 * Android 16 devices can expose a PulseAudio TCP server while its AAudio or
 * OpenSL ES sink has failed and only auto_null remains. A PulseAudio pipe sink
 * avoids those native PulseAudio backends; AudioTrack then uses Android's own
 * media output routing (speaker, wired headset, or Bluetooth).
 */
internal object AndroidPcmAudioOutput {
    private const val TAG = "DroidDeskAudio"
    const val SINK_NAME = "droiddesk_android"
    const val SAMPLE_RATE = 48_000
    private const val BYTES_PER_FRAME = 4

    private class Session(
        val fifo: File,
        val track: AudioTrack,
        val running: AtomicBoolean = AtomicBoolean(true),
    ) {
        @Volatile var input: FileInputStream? = null
        @Volatile var reader: Thread? = null
    }

    @Volatile
    private var activeSession: Session? = null

    @Synchronized
    fun start(fifo: File): Boolean {
        val current = activeSession
        if (current != null &&
            current.running.get() &&
            current.fifo.absolutePath == fifo.absolutePath &&
            current.track.state == AudioTrack.STATE_INITIALIZED
        ) {
            return true
        }

        stopLocked()

        var track: AudioTrack? = null
        try {
            val parent = fifo.parentFile
                ?: throw IOException("Audio FIFO has no parent directory")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create the audio runtime directory")
            }
            if (!parent.isDirectory) {
                throw IOException("Audio runtime path is not a directory")
            }

            if (Files.isSymbolicLink(fifo.toPath())) {
                throw IOException("Refusing a symbolic link as the audio FIFO")
            }
            if (fifo.exists() && !fifo.delete()) {
                throw IOException("Could not remove the previous audio FIFO")
            }

            // Restrict the FIFO to the DroidDesk app UID.
            Os.mkfifo(
                fifo.absolutePath,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR,
            )

            val minimum = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) {
                throw IOException("Android AudioTrack rejected the 48 kHz stereo PCM format ($minimum)")
            }

            // Keep the Android playback queue modest to reduce A/V latency.
            // Two minimum buffers retain scheduling headroom without the old
            // 4x buffer, which could hold several hundred milliseconds of audio.
            val bufferSize = (minimum * 2)
                .coerceIn(8 * 1024, 64 * 1024)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                throw IOException("Android AudioTrack could not initialize")
            }

            track.setVolume(1.0f)
            track.play()

            val session = Session(fifo, track)
            activeSession = session
            session.reader = thread(
                start = true,
                isDaemon = true,
                name = "DroidDesk-Pcm-Audio",
            ) {
                consumePcm(session)
            }

            Log.i(
                TAG,
                "Android AudioTrack ready: ${SAMPLE_RATE}Hz, stereo PCM16, buffer=${bufferSize}B, FIFO=${fifo.absolutePath}",
            )
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Could not start Android PCM audio output", error)
            runCatching { track?.release() }
            runCatching { fifo.delete() }
            activeSession = null
            return false
        }
    }

    private fun consumePcm(session: Session) {
        // Smaller writes prevent large PCM bursts from accumulating ahead of video.
        val buffer = ByteArray(4 * 1024)

        while (session.running.get()) {
            var stream: FileInputStream? = null
            try {
                // Opening a FIFO for reading waits until PulseAudio opens its
                // writer, so no PCM data is lost during server startup.
                stream = FileInputStream(session.fifo)
                session.input = stream
                var pendingBytes = 0
                Log.i(TAG, "PulseAudio pipe sink connected to Android AudioTrack")

                while (session.running.get()) {
                    val count = stream.read(
                        buffer,
                        pendingBytes,
                        buffer.size - pendingBytes,
                    )
                    if (count < 0) break
                    if (count == 0) continue

                    val availableBytes = pendingBytes + count
                    val alignedCount = availableBytes - (availableBytes % BYTES_PER_FRAME)
                    var offset = 0
                    while (offset < alignedCount && session.running.get()) {
                        val written = session.track.write(
                            buffer,
                            offset,
                            alignedCount - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (written < 0) {
                            throw IOException("AudioTrack.write failed with code $written")
                        }
                        if (written == 0) {
                            Thread.sleep(2)
                        } else {
                            offset += written
                        }
                    }

                    // Preserve the trailing bytes until the next FIFO read so
                    // every AudioTrack write starts on a complete stereo frame.
                    pendingBytes = availableBytes - alignedCount
                    if (pendingBytes > 0) {
                        System.arraycopy(
                            buffer,
                            alignedCount,
                            buffer,
                            0,
                            pendingBytes,
                        )
                    }
                }
            } catch (error: Exception) {
                if (session.running.get()) {
                    Log.w(TAG, "Android PCM audio stream interrupted: ${error.message}")
                }
            } finally {
                session.input = null
                runCatching { stream?.close() }
            }

            if (session.running.get()) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        Log.i(TAG, "Android PCM audio reader stopped")
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val session = activeSession ?: return
        activeSession = null
        session.running.set(false)

        runCatching { session.input?.close() }

        // If the reader is waiting for a FIFO writer because PulseAudio failed
        // during startup, a short read/write open releases the blocking open().
        runCatching {
            val descriptor = Os.open(
                session.fifo.absolutePath,
                OsConstants.O_RDWR or OsConstants.O_NONBLOCK,
                0,
            )
            Os.close(descriptor)
        }

        runCatching { session.reader?.join(1_500) }
        runCatching { session.track.pause() }
        runCatching { session.track.flush() }
        runCatching { session.track.stop() }
        runCatching { session.track.release() }
        runCatching { session.fifo.delete() }
    }
}
