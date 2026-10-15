package com.sqwado.sonyscreen.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bulk pipe used by the PTP session. [read] returns the number of bytes copied,
 * 0 for a zero-length packet, or a negative value on error.
 */
interface BulkPipe {
    val maxPacketIn: Int
    val isClosed: Boolean
    fun write(buffer: ByteArray, timeoutMs: Int)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    /** Non-blocking-ish interrupt IN read; 0 = no event, >0 = bytes, <0 = error. */
    fun pollInterrupt(buffer: ByteArray, timeoutMs: Int): Int = 0
    fun recoverStall()
    fun shutdown()
}

enum class PtpFailure {
    PC_REMOTE,
    DISCONNECTED,
    PROTOCOL,
    BUSY,
}

class PtpException(message: String, val failure: PtpFailure) : Exception(message)

object PtpCodec {
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    /**
     * Virtual host-transfer object (not a card file handle).
     * In PC + camera with RAW only, this is the full RAW. Read it once and drop it.
     */
    val captureHandle: Int = 0xFFFFC001.toInt()

    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_SDIO_CONNECT = 0x9201
    const val OC_SDIO_EXT_INFO = 0x9202
    const val OC_SDIO_SET_PROP = 0x9205
    const val OC_SDIO_ALL_PROPS = 0x9209

    const val RC_OK = 0x2001
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INVALID_HANDLE = 0x2009
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    /** Sony: the live-view frame is not ready yet. */
    const val RC_SONY_OBJECT_NOT_READY = 0xA102

    const val EC_SONY_OBJECT_ADDED = 0xC201
    const val EC_SONY_PROP_CHANGED = 0xC203
    const val EC_OBJECT_ADDED = 0x4002

    const val PROP_LIVE_VIEW_STATUS = 0xD221
    const val PROP_SHOOTING_FILE_INFO = 0xD215
    /** Still destination: 0x0001 camera, 0x0010 PC, 0x0011 PC + camera. */
    const val PROP_STILL_DESTINATION = 0xD222
    const val STILL_DEST_PC_AND_CAMERA = 0x0011
    const val SHOOTING_READY_FOR_TRANSFER = 0x8000
    /** SDI 2.0, used by the A7R III. */
    const val EXT_INFO_VERSION = 0x00C8
    /** SDI 3.0, used by newer bodies such as the A7R V. */
    const val EXT_INFO_VERSION_V3 = 0x012C
    const val MAX_CONTAINER_BYTES = 2 * 1024 * 1024
    const val MAX_CAPTURE_BYTES = 200L * 1024L * 1024L

    /** Virtual live-view object. Bit pattern of unsigned 0xFFFFC002. */
    val liveViewHandle: Int = 0xFFFFC002.toInt()

    fun container(type: Int, code: Int, transactionId: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(12 + payload.size)
        buffer.putShort(type.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(transactionId)
        buffer.put(payload)
        return buffer.array()
    }

    fun params(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putInt(value)
        return buffer.array()
    }

    /**
     * Sony live-view objects prefix the JPEG with a little-endian offset.
     * The picture itself is the SOI..EOI slice, which is what the camera actually sent.
     */
    fun extractJpeg(data: ByteArray): ByteArray? {
        if (data.size < 4) return null
        val hinted = u32(data, 0).toInt()
        val hintedStart = if (hinted in 0 until data.size - 1) hinted else 0
        val start = findMarker(data, hintedStart, 0xD8)
            ?: findMarker(data, 0, 0xD8)
            ?: return null
        var end = -1
        var index = data.size - 2
        while (index >= start + 2) {
            if (data[index] == 0xFF.toByte() && data[index + 1] == 0xD9.toByte()) {
                end = index + 2
                break
            }
            index--
        }
        if (end <= start + 2) return null
        return data.copyOfRange(start, end)
    }

    fun extInfoHasLiveView(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        var offset = 0
        while (offset + 1 < payload.size) {
            if (u16(payload, offset) == PROP_LIVE_VIEW_STATUS) return true
            offset += 2
        }
        return false
    }

    /**
     * Current LiveViewStatus (0xD221), or null when the property blob cannot be read.
     * Zero means the camera still owns the monitor and will not answer GetObjectInfo.
     */
    fun liveViewStatus(payload: ByteArray): Long? {
        val walked = try {
            currentProperty(payload, PROP_LIVE_VIEW_STATUS)
        } catch (_: PtpParseException) {
            null
        }
        return walked ?: scanLiveViewStatus(payload)
    }

    /** True when SDIO property 0xD221 is present and its current value is not zero. */
    fun liveViewReady(payload: ByteArray): Boolean = (liveViewStatus(payload) ?: 0L) != 0L

    /**
     * ObjectInMemory (0xD215).
     * 0x0000: nothing waiting. 0x8000: the body is still writing.
     * Any other value with low bits set (0x0001, 0x8001, …) is a still on 0xFFFFC001.
     */
    fun shootingFileInfo(payload: ByteArray): Long? {
        val walked = try {
            currentProperty(payload, PROP_SHOOTING_FILE_INFO)
        } catch (_: PtpParseException) {
            null
        }
        return walked ?: scanShootingFileInfo(payload)
    }

    fun parseModel(payload: ByteArray): String? {
        return try {
            val cursor = ByteCursor(payload)
            cursor.u16()
            cursor.u32()
            cursor.u16()
            cursor.skipString()
            cursor.u16()
            repeat(5) { cursor.skipU16Array() }
            cursor.skipString()
            cursor.readString().ifBlank { null }
        } catch (_: PtpParseException) {
            null
        }
    }

    private fun findMarker(data: ByteArray, from: Int, second: Int): Int? {
        var index = from
        while (index < data.size - 1) {
            if (data[index] == 0xFF.toByte() && data[index + 1] == second.toByte()) return index
            index++
        }
        return null
    }
}

internal class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray,
)

internal class PtpResult(val payload: ByteArray?, val code: Int)

/**
 * One PTP/SDIO session. Every call must come from the same thread.
 */
class PtpSession(
    private val pipe: BulkPipe,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    private var transaction = 0
    private var sessionOpen = false
    private var objectReads = 0
    private var liveViewSeen = false
    private var pendingDrains = 0
    private var deniedLogs = 0
    private var reportedLiveView: String? = null
    private var cameraSaveAttempted = false
    private val readChunk = ByteArray(64 * 1024)
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    /**
     * Opens the Sony SDIO session.
     * Still destination is set later, once 0xD221 is on: doing it while the
     * body is still initialising is overwritten when PC Remote actually starts.
     */
    fun handshake(): String? {
        exchange(PtpCodec.OC_OPEN_SESSION, intArrayOf(1), accept = OPEN_OK, timeoutMs = HANDSHAKE_TIMEOUT_MS)
        sessionOpen = true
        sdioConnect(1)
        sdioConnect(2)
        extDeviceInfo()
        sdioConnect(3)
        pollLiveViewStatus()
        log("PTP handshake complete")
        return null
    }

    /**
     * Latest live-view JPEG, or null while the body is busy or a still is being discarded.
     * Nothing is written to the phone.
     */
    fun nextLiveViewJpeg(): ByteArray? {
        drainInterrupt()
        if (pendingDrains > 0) {
            pendingDrains--
            drainSingleObject()
            pause(AFTER_DRAIN_WAIT_MS)
            return null
        }
        return try {
            val image = exchange(
                PtpCodec.OC_GET_OBJECT,
                intArrayOf(PtpCodec.liveViewHandle),
                accept = LIVE_VIEW_RESPONSES,
                timeoutMs = FRAME_TIMEOUT_MS,
                soft = true,
            )
            if (pendingDrains > 0) {
                pendingDrains--
                drainSingleObject()
                pause(AFTER_DRAIN_WAIT_MS)
                return null
            }
            when (image.code) {
                PtpCodec.RC_OK -> {
                    liveViewSeen = true
                    image.payload?.let(PtpCodec::extractJpeg)
                }
                TRANSPORT_GAP -> {
                    pause(NOT_READY_WAIT_MS)
                    null
                }
                PtpCodec.RC_ACCESS_DENIED, PtpCodec.RC_SONY_OBJECT_NOT_READY -> {
                    if (deniedLogs < 4) {
                        deniedLogs++
                        log("PTP live view not ready rc=${image.code.toHex()}")
                    }
                    if (!liveViewSeen) {
                        pollLiveViewStatus()
                        pause(COLD_START_WAIT_MS)
                    } else {
                        pause(NOT_READY_WAIT_MS)
                    }
                    null
                }
                else -> {
                    pause(NOT_READY_WAIT_MS)
                    null
                }
            }
        } catch (error: PtpException) {
            when (error.failure) {
                PtpFailure.PC_REMOTE -> throw error
                PtpFailure.DISCONNECTED -> if (pipe.isClosed) throw error else null
                PtpFailure.BUSY, PtpFailure.PROTOCOL -> null
            }
        }
    }

    fun closeSession() {
        if (!sessionOpen || pipe.isClosed) return
        sessionOpen = false
        try {
            exchange(PtpCodec.OC_CLOSE_SESSION, timeoutMs = 400)
        } catch (_: Exception) {
        }
    }

    /** Reads 0xD221. A failure here does not close the session. */
    private fun pollLiveViewStatus() {
        val props = try {
            exchange(
                PtpCodec.OC_SDIO_ALL_PROPS,
                accept = LIVE_VIEW_RESPONSES,
                timeoutMs = 800,
                soft = true,
            )
        } catch (error: PtpException) {
            if (error.failure == PtpFailure.DISCONNECTED && pipe.isClosed) throw error
            log("PTP live view status skipped: ${error.message}")
            return
        }
        if (props.code != PtpCodec.RC_OK) return
        val status = props.payload?.let(PtpCodec::liveViewStatus)
        if (status != null && status != 0L) ensureCameraSave()
        val label = status?.let { "0x%X".format(it) } ?: "absent"
        if (label == reportedLiveView) return
        reportedLiveView = label
        log("PTP live view D221=$label")
    }

    /**
     * One 0xC201 on 0xFFFFC001, one object.
     * A second GetObjectInfo on an empty handle reboots the A7R III.
     * RAW+JPEG delivers a second interrupt event for the next file.
     */
    private fun drainSingleObject() {
        log("PTP === START SINGLE DRAIN 0xFFFFC001 ===")
        try {
            val info = exchange(
                PtpCodec.OC_GET_OBJECT_INFO,
                intArrayOf(PtpCodec.captureHandle),
                accept = LIVE_VIEW_RESPONSES,
                timeoutMs = 1_500,
                soft = true,
            )
            log(
                "PTP Drain GetObjectInfo(0x1008) rc=${info.code.toHex()} bytes=${info.payload?.size ?: 0}",
            )
            if (info.code == PtpCodec.RC_OK) {
                val discarded = discardGetObject(PtpCodec.captureHandle)
                log(
                    "PTP Drain GetObject(0x1009) rc=${discarded.code.toHex()} bytes=${discarded.bytes}",
                )
            }
            drainInterrupt()
            acknowledgeProperties()
            log("PTP === DRAIN COMPLETE & UNLOCKED ===")
        } catch (error: PtpException) {
            if (error.failure == PtpFailure.DISCONNECTED && pipe.isClosed) throw error
            log("PTP capture discard failed: ${error.message}")
        }
    }

    /**
     * Reading 0x9209 acknowledges the property change that follows a still.
     * Without it the A7R III keeps live view in 0x200F for about five seconds.
     */
    private fun acknowledgeProperties() {
        val props = try {
            exchange(
                PtpCodec.OC_SDIO_ALL_PROPS,
                accept = LIVE_VIEW_RESPONSES,
                timeoutMs = 800,
                soft = true,
            )
        } catch (error: PtpException) {
            if (error.failure == PtpFailure.DISCONNECTED && pipe.isClosed) throw error
            log("PTP unlock 0x9209 skipped: ${error.message}")
            return
        }
        log("PTP unlock 0x9209 rc=${props.code.toHex()} bytes=${props.payload?.size ?: 0}")
    }

    /** Drain the interrupt IN pipe so the camera event buffer cannot stall USB. */
    private fun drainInterrupt() {
        val buffer = ByteArray(64)
        repeat(8) {
            val read = pipe.pollInterrupt(buffer, INTERRUPT_TIMEOUT_MS)
            if (read <= 0) return
            if (read >= 12 && u16(buffer, 4) == PtpCodec.TYPE_EVENT) {
                val payload = if (read > 12) buffer.copyOfRange(12, read) else ByteArray(0)
                noteCaptureEvent(u16(buffer, 6), payload)
            }
        }
    }

    private fun discardGetObject(handle: Int): Discarded {
        val transactionId = ++transaction
        writeContainer(
            PtpCodec.TYPE_COMMAND,
            PtpCodec.OC_GET_OBJECT,
            transactionId,
            PtpCodec.params(intArrayOf(handle)),
            CAPTURE_TIMEOUT_MS,
        )
        var skipped = 0
        var discarded = 0
        while (true) {
            val header = readExact(12, CAPTURE_TIMEOUT_MS, soft = false)
            val length = u32(header, 0)
            if (length < 12L || length > PtpCodec.MAX_CAPTURE_BYTES) {
                throw PtpException("Invalid capture container length $length.", PtpFailure.PROTOCOL)
            }
            val payloadBytes = (length - 12L).toInt()
            if (payloadBytes > 0) readDiscard(payloadBytes, CAPTURE_TIMEOUT_MS)
            val type = u16(header, 4)
            val code = u16(header, 6)
            val tx = u32(header, 8).toInt()
            if (type == PtpCodec.TYPE_EVENT) {
                noteCaptureEvent(code, ByteArray(0))
                continue
            }
            if (tx != transactionId) {
                if (++skipped > 32) {
                    throw PtpException("PTP capture transaction id drifted.", PtpFailure.PROTOCOL)
                }
                continue
            }
            when (type) {
                PtpCodec.TYPE_DATA -> discarded += payloadBytes
                PtpCodec.TYPE_RESPONSE -> {
                    if (code != PtpCodec.RC_OK && code !in LIVE_VIEW_OK) {
                        throw failureFor(PtpCodec.OC_GET_OBJECT, code)
                    }
                    return Discarded(code, discarded)
                }
                else -> {
                    if (++skipped > 32) {
                        throw PtpException("Unexpected capture container type $type.", PtpFailure.PROTOCOL)
                    }
                }
            }
        }
    }

    private fun readDiscard(size: Int, timeoutMs: Int) {
        var left = size
        var zeroPackets = 0
        var failures = 0
        var nextLog = 8 * 1024 * 1024
        while (left > 0) {
            if (pendingOffset < pending.size) {
                val count = minOf(left, pending.size - pendingOffset)
                pendingOffset += count
                left -= count
                continue
            }
            val read = try {
                pipe.read(readChunk, timeoutMs)
            } catch (error: Exception) {
                throw PtpException(error.message ?: "USB read failed.", PtpFailure.DISCONNECTED)
            }
            when {
                read == 0 -> {
                    if (++zeroPackets > 8) {
                        throw PtpException("Repeated zero-length USB packets.", PtpFailure.PROTOCOL)
                    }
                }
                read < 0 -> {
                    if (++failures >= 8) {
                        throw PtpException("USB read failed while discarding still.", PtpFailure.DISCONNECTED)
                    }
                    pause(20)
                }
                else -> {
                    zeroPackets = 0
                    failures = 0
                    val take = minOf(left, read)
                    left -= take
                    if (take < read) {
                        pending = readChunk.copyOfRange(take, read)
                        pendingOffset = 0
                    }
                    val done = size - left
                    if (done >= nextLog) {
                        nextLog += 8 * 1024 * 1024
                        log("PTP discard progress done=$done left=$left")
                    }
                }
            }
        }
    }

    private fun noteCaptureEvent(code: Int, payload: ByteArray) {
        if (code != PtpCodec.EC_SONY_OBJECT_ADDED && code != PtpCodec.EC_OBJECT_ADDED) return
        val handle = if (payload.size >= 4) u32(payload, 0).toInt() else 0
        log("PTP object added handle=0x${"%08X".format(handle)}")
        if (handle == PtpCodec.captureHandle) pendingDrains++
    }

    private fun sdioConnect(phase: Int) {
        exchange(
            PtpCodec.OC_SDIO_CONNECT,
            intArrayOf(phase, 0, 0),
            timeoutMs = HANDSHAKE_TIMEOUT_MS,
        )
    }

    /**
     * Negotiate SDI. A7R III answers 0x00C8; newer bodies may require 0x012C.
     */
    private fun extDeviceInfo(): ByteArray {
        var refused: PtpException? = null
        for (version in intArrayOf(PtpCodec.EXT_INFO_VERSION, PtpCodec.EXT_INFO_VERSION_V3)) {
            try {
                val result = exchange(
                    PtpCodec.OC_SDIO_EXT_INFO,
                    intArrayOf(version),
                    timeoutMs = HANDSHAKE_TIMEOUT_MS,
                )
                log("PTP ext device info 0x${"%04X".format(version)}")
                return result.payload ?: byteArrayOf()
            } catch (error: PtpException) {
                if (error.failure == PtpFailure.DISCONNECTED) throw error
                if (error.failure != PtpFailure.PC_REMOTE) throw error
                refused = error
                log("PTP ext device info 0x${"%04X".format(version)} refused")
            }
        }
        throw refused ?: PtpException("SDIO ext info refused.", PtpFailure.PC_REMOTE)
    }

    /** 0xD222 = 0x0011, sent once 0xD221 has left zero. */
    private fun ensureCameraSave() {
        if (cameraSaveAttempted) return
        cameraSaveAttempted = true
        forceCameraSave()
    }

    /** 0xD222 = 0x0011 keeps the RAW on the card and on the host pipe. */
    private fun forceCameraSave() {
        val value = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(PtpCodec.STILL_DEST_PC_AND_CAMERA.toShort())
            .array()
        try {
            exchange(
                PtpCodec.OC_SDIO_SET_PROP,
                intArrayOf(PtpCodec.PROP_STILL_DESTINATION),
                outgoing = value,
                timeoutMs = HANDSHAKE_TIMEOUT_MS,
            )
            log("PTP still destination PC and camera")
        } catch (error: PtpException) {
            if (error.failure == PtpFailure.DISCONNECTED) throw error
            log("PTP still destination not set: ${error.message}")
        }
    }

    private fun exchange(
        operation: Int,
        params: IntArray = intArrayOf(),
        accept: Set<Int> = setOf(PtpCodec.RC_OK),
        outgoing: ByteArray? = null,
        timeoutMs: Int,
        soft: Boolean = false,
    ): PtpResult {
        var busy = 0
        while (true) {
            val result = once(operation, params, outgoing, timeoutMs, soft)
            if (result.code in accept) return result
            if (result.code == PtpCodec.RC_DEVICE_BUSY && busy++ < 3) {
                pause(30)
                continue
            }
            throw failureFor(operation, result.code)
        }
    }

    private fun once(
        operation: Int,
        params: IntArray,
        outgoing: ByteArray?,
        timeoutMs: Int,
        soft: Boolean = false,
    ): PtpResult {
        val transactionId = if (operation == PtpCodec.OC_OPEN_SESSION) 0 else ++transaction
        val capture = params.firstOrNull() == PtpCodec.captureHandle
        val quiet = !capture && (operation == PtpCodec.OC_GET_OBJECT || operation == PtpCodec.OC_GET_OBJECT_INFO)
        if (quiet) objectReads++
        val trace = !quiet || objectReads <= 4 || objectReads % 40 == 0
        if (trace) log("PTP -> ${operation.toHex()} tx=$transactionId")
        writeContainer(PtpCodec.TYPE_COMMAND, operation, transactionId, PtpCodec.params(params), timeoutMs)
        if (outgoing != null) {
            writeContainer(PtpCodec.TYPE_DATA, operation, transactionId, outgoing, timeoutMs)
        }
        var data: ByteArray? = null
        var skipped = 0
        while (true) {
            val container = try {
                readContainer(timeoutMs, soft)
            } catch (error: PtpException) {
                if (soft && error.failure != PtpFailure.PC_REMOTE && !pipe.isClosed) {
                    // Transport gap, not a camera response. Do not probe the capture handle.
                    return PtpResult(null, TRANSPORT_GAP)
                }
                throw error
            }
            if (container.type == PtpCodec.TYPE_EVENT) {
                noteCaptureEvent(container.code, container.payload)
                continue
            }
            if (container.transactionId != transactionId) {
                if (++skipped > 32) {
                    throw PtpException("PTP transaction id drifted.", PtpFailure.PROTOCOL)
                }
                continue
            }
            when (container.type) {
                PtpCodec.TYPE_DATA -> data = container.payload
                PtpCodec.TYPE_RESPONSE -> {
                    if (trace) {
                        log("PTP <- ${operation.toHex()} tx=$transactionId rc=${container.code.toHex()} bytes=${data?.size ?: 0}")
                    }
                    return PtpResult(data, container.code)
                }
                else -> {
                    if (++skipped > 32) {
                        throw PtpException("Unexpected PTP container type ${container.type}.", PtpFailure.PROTOCOL)
                    }
                }
            }
        }
    }

    private fun writeContainer(type: Int, code: Int, transactionId: Int, payload: ByteArray, timeoutMs: Int) {
        try {
            pipe.write(PtpCodec.container(type, code, transactionId, payload), timeoutMs)
        } catch (error: PtpException) {
            throw error
        } catch (error: Exception) {
            throw PtpException(error.message ?: "USB write failed.", PtpFailure.DISCONNECTED)
        }
    }

    internal fun readContainer(timeoutMs: Int = 1000, soft: Boolean = false): PtpContainer {
        val header = readExact(12, timeoutMs, soft)
        val length = u32(header, 0)
        if (length < 12 || length > PtpCodec.MAX_CONTAINER_BYTES) {
            throw PtpException("Invalid PTP container length $length.", PtpFailure.PROTOCOL)
        }
        val payload = if (length == 12L) {
            ByteArray(0)
        } else {
            readExact((length - 12).toInt(), timeoutMs, soft)
        }
        return PtpContainer(
            type = u16(header, 4),
            code = u16(header, 6),
            transactionId = u32(header, 8).toInt(),
            payload = payload,
        )
    }

    private fun readExact(size: Int, timeoutMs: Int, soft: Boolean = false): ByteArray {
        val output = ByteArray(size)
        var filled = 0
        var zeroPackets = 0
        var failures = 0
        while (filled < size) {
            if (pendingOffset < pending.size) {
                val count = minOf(size - filled, pending.size - pendingOffset)
                pending.copyInto(output, filled, pendingOffset, pendingOffset + count)
                pendingOffset += count
                filled += count
                continue
            }
            val read = try {
                pipe.read(readChunk, timeoutMs)
            } catch (error: Exception) {
                throw PtpException(error.message ?: "USB read failed.", PtpFailure.DISCONNECTED)
            }
            if (read == 0) {
                if (++zeroPackets > 8) {
                    throw PtpException("Repeated zero-length USB packets.", PtpFailure.PROTOCOL)
                }
                continue
            }
            if (read < 0) {
                if (soft) {
                    // One full bulk timeout — treat as temporary, never CLEAR_HALT.
                    throw PtpException("USB read timed out.", PtpFailure.BUSY)
                }
                if (++failures >= 4) {
                    throw PtpException("USB read failed.", PtpFailure.DISCONNECTED)
                }
                pipe.recoverStall()
                pause(20)
                continue
            }
            zeroPackets = 0
            failures = 0
            val take = minOf(size - filled, read)
            readChunk.copyInto(output, filled, 0, take)
            filled += take
            if (take < read) {
                pending = readChunk.copyOfRange(take, read)
                pendingOffset = 0
            }
        }
        return output
    }

    private fun failureFor(operation: Int, code: Int): PtpException {
        val failure = when (code) {
            PtpCodec.RC_DEVICE_BUSY -> PtpFailure.BUSY
            PtpCodec.RC_OPERATION_NOT_SUPPORTED,
            PtpCodec.RC_PARAMETER_NOT_SUPPORTED,
            PtpCodec.RC_SESSION_NOT_OPEN,
            -> PtpFailure.PC_REMOTE
            else -> PtpFailure.PROTOCOL
        }
        return PtpException("PTP ${operation.toHex()} rejected with ${code.toHex()}.", failure)
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 2500
        const val FRAME_TIMEOUT_MS = 1500
        const val CAPTURE_TIMEOUT_MS = 60_000
        const val INTERRUPT_TIMEOUT_MS = 8
        const val NOT_READY_WAIT_MS = 10L
        const val COLD_START_WAIT_MS = 50L
        const val AFTER_DRAIN_WAIT_MS = 20L
        /** Soft USB timeout. Not a PTP response code, so it must not trigger a still purge. */
        const val TRANSPORT_GAP = -1
        val OPEN_OK = setOf(PtpCodec.RC_OK, PtpCodec.RC_SESSION_ALREADY_OPEN)
        val LIVE_VIEW_OK = setOf(
            PtpCodec.RC_OK,
            PtpCodec.RC_INVALID_HANDLE,
            PtpCodec.RC_ACCESS_DENIED,
            PtpCodec.RC_DEVICE_BUSY,
            PtpCodec.RC_SONY_OBJECT_NOT_READY,
        )
        val LIVE_VIEW_RESPONSES = LIVE_VIEW_OK + TRANSPORT_GAP
    }
}

private class Discarded(val code: Int, val bytes: Int)

private class PtpParseException : Exception()

private fun scanShootingFileInfo(payload: ByteArray): Long? {
    var offset = 0
    while (offset + 10 < payload.size) {
        if (u16(payload, offset) == PtpCodec.PROP_SHOOTING_FILE_INFO) {
            val type = u16(payload, offset + 2)
            if ((type == 0x0003 || type == 0x0004) && offset + 9 < payload.size) {
                return u16(payload, offset + 8).toLong()
            }
        }
        offset += 2
    }
    return null
}

private fun scanLiveViewStatus(payload: ByteArray): Long? {
    var offset = 0
    while (offset + 8 < payload.size) {
        if (u16(payload, offset) == PtpCodec.PROP_LIVE_VIEW_STATUS) {
            val type = u16(payload, offset + 2)
            val getSet = payload[offset + 4].toInt() and 0xFF
            val enabled = payload[offset + 5].toInt() and 0xFF
            if (getSet <= 1 && enabled <= 2) {
                val valueAt = offset + 6
                val value = when (type) {
                    0x0001, 0x0002 ->
                        if (valueAt + 1 < payload.size) payload[valueAt + 1].toLong() and 0xFF else null
                    0x0003, 0x0004 ->
                        if (valueAt + 3 < payload.size) u16(payload, valueAt + 2).toLong() else null
                    0x0005, 0x0006 ->
                        if (valueAt + 7 < payload.size) u32(payload, valueAt + 4) else null
                    else -> null
                }
                if (value != null) return value
            }
        }
        offset += 2
    }
    return null
}

/**
 * Walks Sony's SDIO property array (opcode 0x9209) enough to read one current value.
 *
 * Count is a UINT64. Each record is property code, datatype, GetSet, IsEnabled,
 * factory default, current value, then a form flag (none, range, or enumeration).
 */
private fun currentProperty(payload: ByteArray, code: Int): Long? {
    val cursor = ByteCursor(payload)
    val declared = cursor.u64()
    val count = if (declared < 0L || declared > 4096L) 4096L else declared
    var index = 0L
    while (index < count && cursor.remaining() > 0) {
        val property = cursor.u16()
        val dataType = cursor.u16()
        cursor.u8()
        cursor.u8()
        cursor.skipValue(dataType)
        val current = cursor.readNumber(dataType)
        val form = cursor.u8()
        when (form) {
            0 -> Unit
            1 -> repeat(3) { cursor.skipValue(dataType) }
            2 -> {
                cursor.skipValueList(dataType)
                if (cursor.remaining() >= 2) cursor.skipValueList(dataType)
            }
            else -> throw PtpParseException()
        }
        if (property == code && current != null) return current
        index++
    }
    return null
}

private class ByteCursor(private val data: ByteArray) {
    private var offset = 0

    fun remaining(): Int = data.size - offset

    fun u8(): Int = read(1) { data[it].toInt() and 0xFF }

    fun u16(): Int = read(2) {
        (data[it].toInt() and 0xFF) or ((data[it + 1].toInt() and 0xFF) shl 8)
    }

    fun u32(): Long = read(4) {
        var value = 0L
        for (index in 3 downTo 0) {
            value = (value shl 8) or (data[it + index].toLong() and 0xFF)
        }
        value
    }

    fun u64(): Long = read(8) {
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (data[it + index].toLong() and 0xFF)
        }
        value
    }

    fun readString(): String {
        val characters = u8()
        if (characters == 0) return ""
        val bytes = read(characters * 2) { start -> data.copyOfRange(start, start + characters * 2) }
        return String(bytes, Charsets.UTF_16LE).trimEnd('\u0000', ' ')
    }

    fun skipString() {
        readString()
    }

    fun skipU16Array() {
        val count = u32()
        if (count < 0L || count > 65536L) throw PtpParseException()
        skip((count * 2).toInt())
    }

    fun skipValue(dataType: Int) {
        readNumber(dataType)
    }

    fun readNumber(dataType: Int): Long? = when (dataType) {
        0x0001 -> (u8().toByte()).toLong()
        0x0002 -> u8().toLong()
        0x0003 -> (u16().toShort()).toLong()
        0x0004 -> u16().toLong()
        0x0005 -> u32().toInt().toLong()
        0x0006 -> u32()
        0x0007, 0x0008 -> u64()
        0x0009, 0x000A -> {
            skip(16)
            null
        }
        0xFFFF -> {
            skipString()
            null
        }
        else -> {
            if (dataType and 0x4000 != 0) {
                skipArray(dataType and 0x4000.inv())
                null
            } else {
                throw PtpParseException()
            }
        }
    }

    fun skipValueList(dataType: Int) {
        val count = u16()
        if (count > 65536) throw PtpParseException()
        repeat(count) { skipValue(dataType) }
    }

    private fun skipArray(elementType: Int) {
        val count = u32()
        if (count < 0L || count > 65536L) throw PtpParseException()
        var index = 0L
        while (index < count) {
            skipValue(elementType)
            index++
        }
    }

    private fun skip(size: Int) {
        read(size) { }
    }

    private fun <T> read(size: Int, decode: (Int) -> T): T {
        if (size < 0 || offset + size > data.size) throw PtpParseException()
        val start = offset
        offset += size
        return decode(start)
    }
}

private fun u16(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun u32(data: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 3 downTo 0) {
        value = (value shl 8) or (data[offset + index].toLong() and 0xFF)
    }
    return value
}

private fun Int.toHex(): String = "0x%04X".format(this)
