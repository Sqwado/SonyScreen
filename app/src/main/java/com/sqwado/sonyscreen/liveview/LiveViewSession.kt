package com.sqwado.sonyscreen.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.sqwado.sonyscreen.ptp.BulkPipe
import com.sqwado.sonyscreen.ptp.PtpException
import com.sqwado.sonyscreen.ptp.PtpFailure
import com.sqwado.sonyscreen.ptp.PtpSession

enum class Notice {
    NEGOTIATING,
    PC_REMOTE,
    DISCONNECTED,
    PROTOCOL,
}

interface LiveViewListener {
    fun onPhase(notice: Notice)
    fun onReady(model: String?)

    /**
     * Called off the main thread. Draw [bitmap] before returning: it is reused for the next frame.
     */
    fun onFrame(bitmap: Bitmap, width: Int, height: Int, fps: Double)

    fun onClosed(session: LiveViewSession)
}

/**
 * USB thread reads PTP live-view JPEGs into a single-slot mailbox.
 * A second thread decodes only the latest frame into a reused bitmap.
 */
class LiveViewSession(
    private val pipe: BulkPipe,
    private val listener: LiveViewListener,
) {
    private val ptp = PtpSession(pipe, log = { message -> Log.d(TAG, message) })
    private val mailbox = Mailbox()

    @Volatile
    private var running = false

    private var reusable: Bitmap? = null
    private var frames = 0
    private var windowStart = 0L
    private var fps = 0.0

    fun start() {
        if (running) return
        running = true
        Thread({ runSession() }, "sony-ptp").start()
        Thread({ decodeLoop() }, "sony-jpeg").start()
    }

    fun stop() {
        running = false
        mailbox.close()
        pipe.shutdown()
    }

    private fun runSession() {
        try {
            listener.onPhase(Notice.NEGOTIATING)
            val model = ptp.handshake()
            if (!running) return
            listener.onReady(model)
            while (running) {
                val started = SystemClock.elapsedRealtime()
                val jpeg = try {
                    ptp.nextLiveViewJpeg()
                } catch (error: PtpException) {
                    if (!running) break
                    if (error.failure == PtpFailure.BUSY) {
                        sleepMs(40)
                        continue
                    }
                    throw error
                }
                if (!running) break
                if (jpeg == null) continue
                mailbox.publish(jpeg)
                val wait = FRAME_PERIOD_MS - (SystemClock.elapsedRealtime() - started)
                if (wait > 0) sleepMs(wait)
            }
        } catch (error: PtpException) {
            Log.w(TAG, "PTP session ended: ${error.message}")
            if (running) listener.onPhase(noticeFor(error))
        } catch (error: Exception) {
            Log.w(TAG, "USB session ended", error)
            if (running) listener.onPhase(Notice.DISCONNECTED)
        } finally {
            if (!pipe.isClosed) ptp.closeSession()
            pipe.shutdown()
            mailbox.close()
            listener.onClosed(this)
        }
    }

    private fun decodeLoop() {
        while (running) {
            val jpeg = mailbox.take() ?: break
            val bitmap = decode(jpeg) ?: continue
            if (!running) break
            present(bitmap)
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inBitmap = reusable?.takeUnless { it.isRecycled }
        }
        decodeBytes(jpeg, options)?.let { return it }
        options.inBitmap = null
        return decodeBytes(jpeg, options)
    }

    private fun present(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) windowStart = now
        frames++
        val elapsed = (now - windowStart).coerceAtLeast(1)
        if (now - windowStart >= 500) {
            fps = frames * 1000.0 / elapsed
            frames = 0
            windowStart = now
        }
        val shownFps = if (fps > 0.0) fps else frames * 1000.0 / elapsed
        listener.onFrame(bitmap, bitmap.width, bitmap.height, shownFps)
        reusable = bitmap.takeUnless { it.isRecycled }
    }

    private fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            running = false
        }
    }

    private fun noticeFor(error: PtpException): Notice = when (error.failure) {
        PtpFailure.PC_REMOTE -> Notice.PC_REMOTE
        PtpFailure.DISCONNECTED -> Notice.DISCONNECTED
        PtpFailure.BUSY, PtpFailure.PROTOCOL -> Notice.PROTOCOL
    }

    private class Mailbox {
        private val lock = Object()
        private var frame: ByteArray? = null
        private var open = true

        fun publish(jpeg: ByteArray) {
            synchronized(lock) {
                if (!open) return
                frame = jpeg
                lock.notifyAll()
            }
        }

        fun take(): ByteArray? {
            synchronized(lock) {
                while (open && frame == null) {
                    try {
                        lock.wait(300)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
                val jpeg = frame
                frame = null
                return jpeg
            }
        }

        fun close() {
            synchronized(lock) {
                open = false
                lock.notifyAll()
            }
        }
    }

    private companion object {
        const val TAG = "SonyScreen"
        const val FRAME_PERIOD_MS = 31L

        fun decodeBytes(jpeg: ByteArray, options: BitmapFactory.Options): Bitmap? {
            return try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
