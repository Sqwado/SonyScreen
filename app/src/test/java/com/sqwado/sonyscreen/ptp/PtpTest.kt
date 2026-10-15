package com.sqwado.sonyscreen.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpTest {
    @Test
    fun openSessionCommandIsLittleEndian() {
        val bytes = PtpCodec.container(
            PtpCodec.TYPE_COMMAND,
            PtpCodec.OC_OPEN_SESSION,
            0,
            PtpCodec.params(intArrayOf(1)),
        )
        assertEquals(16, bytes.size)
        assertEquals(16, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[1].toInt())
        assertEquals(PtpCodec.TYPE_COMMAND, bytes[4].toInt())
        assertEquals(0x02, bytes[6].toInt() and 0xFF)
        assertEquals(0x10, bytes[7].toInt() and 0xFF)
        assertEquals(0, bytes[8].toInt())
        assertEquals(1, bytes[12].toInt())
    }

    @Test
    fun extractJpegUsesHintAndDropsBytesAfterEoi() {
        val jpeg = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0x11,
            0x22,
            0xFF.toByte(),
            0xD9.toByte(),
        )
        val payload = ByteBuffer.allocate(4 + jpeg.size + 3).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(4)
        payload.put(jpeg)
        payload.put(byteArrayOf(1, 2, 3))

        val extracted = PtpCodec.extractJpeg(payload.array())
        assertArrayEquals(jpeg, extracted)
    }

    @Test
    fun extractJpegReturnsNullWithoutSoi() {
        assertNull(PtpCodec.extractJpeg(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun extInfoDetectsLiveViewProperty() {
        val withLiveView = byteArrayOf(0xC8.toByte(), 0x00, 0x21, 0xD2.toByte())
        val without = byteArrayOf(0xC8.toByte(), 0x00, 0x01, 0x00)
        assertTrue(PtpCodec.extInfoHasLiveView(withLiveView))
        assertFalse(PtpCodec.extInfoHasLiveView(without))
        assertFalse(PtpCodec.extInfoHasLiveView(byteArrayOf(0x21)))
    }

    @Test
    fun liveViewReadyReadsSecondProperty() {
        assertTrue(PtpCodec.liveViewReady(liveViewProps(current = 1)))
        assertFalse(PtpCodec.liveViewReady(liveViewProps(current = 0)))
    }

    @Test
    fun leftoverBytesStayAvailableForTheNextContainer() {
        val first = PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 4, ByteArray(0))
        val second = PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 5, ByteArray(0))
        val pipe = ScriptedPipe(listOf(first + second))
        val session = PtpSession(pipe, pause = { _ -> })

        assertEquals(4, session.readContainer().transactionId)
        assertEquals(5, session.readContainer().transactionId)
        assertEquals(1, pipe.reads)
    }

    @Test
    fun splitContainerIsReassembledAndZeroLengthPacketIsFraming() {
        val payload = ByteArray(200) { it.toByte() }
        val data = PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 7, payload)
        val response = PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 7, ByteArray(0))
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 60, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        chunks.add(ByteArray(0))
        chunks.add(response)
        val session = PtpSession(ScriptedPipe(chunks), pause = { _ -> })

        val container = session.readContainer()
        assertEquals(PtpCodec.TYPE_DATA, container.type)
        assertArrayEquals(payload, container.payload)
        val end = session.readContainer()
        assertEquals(PtpCodec.RC_OK, end.code)
        assertEquals(7, end.transactionId)
    }

    @Test
    fun handshakeWaitsToSetStillDestination() {
        val transfers = ArrayList<ByteArray>()
        transfers += response(PtpCodec.RC_OK, tx = 0)
        transfers += response(PtpCodec.RC_OK, tx = 1)
        transfers += response(PtpCodec.RC_OK, tx = 2)
        transfers += response(PtpCodec.RC_OK, tx = 3, data = extInfo(), op = PtpCodec.OC_SDIO_EXT_INFO)
        transfers += response(PtpCodec.RC_OK, tx = 4)
        transfers += response(PtpCodec.RC_OK, tx = 5)
        val pipe = ScriptedPipe(transfers)
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.handshake())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(
            listOf(
                PtpCodec.OC_OPEN_SESSION,
                PtpCodec.OC_SDIO_CONNECT,
                PtpCodec.OC_SDIO_CONNECT,
                PtpCodec.OC_SDIO_EXT_INFO,
                PtpCodec.OC_SDIO_CONNECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
            ),
            commands.map(::opcode),
        )
        assertEquals(0, transactionId(pipe.written[0]))
        assertEquals(1, param(commands[1]))
        assertEquals(2, param(commands[2]))
        assertEquals(PtpCodec.EXT_INFO_VERSION, param(commands[3]))
        assertEquals(3, param(commands[4]))
        assertTrue(pipe.written.none { opcode(it) == PtpCodec.OC_SDIO_SET_PROP })
    }

    @Test
    fun extInfoFallsBackToV3WhenMarkThreeVersionIsRefused() {
        val transfers = ArrayList<ByteArray>()
        transfers += response(PtpCodec.RC_OK, tx = 0)
        transfers += response(PtpCodec.RC_OK, tx = 1)
        transfers += response(PtpCodec.RC_OK, tx = 2)
        transfers += response(PtpCodec.RC_PARAMETER_NOT_SUPPORTED, tx = 3)
        transfers += response(PtpCodec.RC_OK, tx = 4, data = extInfo(), op = PtpCodec.OC_SDIO_EXT_INFO)
        transfers += response(PtpCodec.RC_OK, tx = 5)
        transfers += response(PtpCodec.RC_OK, tx = 6)
        val pipe = ScriptedPipe(transfers)
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.handshake())

        val extInfo = pipe.written.filter {
            containerType(it) == PtpCodec.TYPE_COMMAND && opcode(it) == PtpCodec.OC_SDIO_EXT_INFO
        }
        assertEquals(listOf(PtpCodec.EXT_INFO_VERSION, PtpCodec.EXT_INFO_VERSION_V3), extInfo.map(::param))
    }

    @Test
    fun extInfoRefusalOnBothVersionsAsksForPcRemote() {
        val transfers = ArrayList<ByteArray>()
        transfers += response(PtpCodec.RC_OK, tx = 0)
        transfers += response(PtpCodec.RC_OK, tx = 1)
        transfers += response(PtpCodec.RC_OK, tx = 2)
        transfers += response(PtpCodec.RC_PARAMETER_NOT_SUPPORTED, tx = 3)
        transfers += response(PtpCodec.RC_PARAMETER_NOT_SUPPORTED, tx = 4)
        val session = PtpSession(ScriptedPipe(transfers), pause = { _ -> })

        val error = runCatching { session.handshake() }.exceptionOrNull()
        assertTrue(error is PtpException)
        assertEquals(PtpFailure.PC_REMOTE, (error as PtpException).failure)
    }

    @Test
    fun stillDestinationIsSetOnceLiveViewStatusTurnsOn() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 1)
        chunks += response(PtpCodec.RC_OK, tx = 2, data = liveViewProps(1), op = PtpCodec.OC_SDIO_ALL_PROPS)
        chunks += response(PtpCodec.RC_OK, tx = 3)
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 4)
        chunks += response(PtpCodec.RC_OK, tx = 5, data = liveViewProps(1), op = PtpCodec.OC_SDIO_ALL_PROPS)
        val pipe = ScriptedPipe(chunks)
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.nextLiveViewJpeg())
        assertNull(session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(
            listOf(
                PtpCodec.OC_GET_OBJECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
                PtpCodec.OC_SDIO_SET_PROP,
                PtpCodec.OC_GET_OBJECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
            ),
            commands.map(::opcode),
        )
        assertEquals(PtpCodec.PROP_STILL_DESTINATION, param(commands[2]))
        val data = pipe.written.first { containerType(it) == PtpCodec.TYPE_DATA }
        assertEquals(PtpCodec.STILL_DEST_PC_AND_CAMERA and 0xFF, data[12].toInt() and 0xFF)
        assertEquals(PtpCodec.STILL_DEST_PC_AND_CAMERA shr 8, data[13].toInt() and 0xFF)
        assertFalse(pipe.isClosed)
    }

    @Test
    fun stillDestinationFailureDoesNotAbortLiveView() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 1)
        chunks += response(PtpCodec.RC_OK, tx = 2, data = liveViewProps(1), op = PtpCodec.OC_SDIO_ALL_PROPS)
        chunks += response(PtpCodec.RC_PARAMETER_NOT_SUPPORTED, tx = 3)
        val pipe = ScriptedPipe(chunks)
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.nextLiveViewJpeg())
        assertFalse(pipe.isClosed)
    }

    @Test
    fun liveViewGetObjectReturnsJpegWithoutOpeningCaptureHandle() {
        val jpeg = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xAA.toByte(),
            0xFF.toByte(),
            0xD9.toByte(),
        )
        val objectBytes = ByteBuffer.allocate(4 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)
            .put(jpeg)
            .array()
        val chunks = ArrayList<ByteArray>()
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 1, objectBytes)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 1, ByteArray(0))
        val pipe = ScriptedPipe(chunks)
        val session = PtpSession(pipe, pause = { _ -> })

        assertArrayEquals(jpeg, session.nextLiveViewJpeg())
        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(listOf(PtpCodec.OC_GET_OBJECT), commands.map(::opcode))
        assertEquals(PtpCodec.liveViewHandle, param(commands[0]))
        assertEquals(8, pipe.interruptTimeoutMs)
        assertFalse(pipe.isClosed)
    }

    @Test
    fun busyLiveViewWaitsWithoutTouchingCaptureHandle() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_DEVICE_BUSY, tx = 1)
        val pipe = ScriptedPipe(chunks)
        val pauses = ArrayList<Long>()
        val session = PtpSession(pipe, pause = { pauses += it })

        assertNull(session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(listOf(PtpCodec.OC_GET_OBJECT), commands.map(::opcode))
        assertEquals(PtpCodec.liveViewHandle, param(commands[0]))
        assertEquals(listOf(10L), pauses)
        assertFalse(pipe.isClosed)
    }

    @Test
    fun objectNotReadyPollsStatusAndDoesNotTouchCaptureHandle() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_SONY_OBJECT_NOT_READY, tx = 1)
        val pipe = ScriptedPipe(chunks)
        val pauses = ArrayList<Long>()
        val session = PtpSession(pipe, pause = { pauses += it })

        assertNull(session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(listOf(PtpCodec.OC_GET_OBJECT, PtpCodec.OC_SDIO_ALL_PROPS), commands.map(::opcode))
        assertEquals(PtpCodec.liveViewHandle, param(commands[0]))
        assertEquals(listOf(50L), pauses)
        assertFalse(pipe.isClosed)
    }

    @Test
    fun accessDeniedDoesNotOpenCaptureHandle() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 1, data = ByteArray(8), op = PtpCodec.OC_GET_OBJECT)
        val pipe = ScriptedPipe(chunks)
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.nextLiveViewJpeg())

        assertTrue(pipe.written.none { it.size >= 16 && param(it) == PtpCodec.captureHandle })
        assertFalse(pipe.isClosed)
    }

    @Test
    fun accessDeniedBeforeFirstFramePollsStatusWithoutCaptureHandle() {
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 1)
        chunks += response(PtpCodec.RC_OK, tx = 2, data = liveViewProps(0), op = PtpCodec.OC_SDIO_ALL_PROPS)
        val pipe = ScriptedPipe(chunks)
        val pauses = ArrayList<Long>()
        val session = PtpSession(pipe, pause = { pauses += it })

        assertNull(session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(listOf(PtpCodec.OC_GET_OBJECT, PtpCodec.OC_SDIO_ALL_PROPS), commands.map(::opcode))
        assertEquals(listOf(50L), pauses)
        assertTrue(pipe.written.none { it.size >= 16 && param(it) == PtpCodec.captureHandle })
        assertFalse(pipe.isClosed)
    }

    @Test
    fun accessDeniedAfterFirstFrameWaitsTenMilliseconds() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0xFF.toByte(), 0xD9.toByte())
        val payload = ByteBuffer.allocate(4 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)
            .put(jpeg)
            .array()
        val chunks = ArrayList<ByteArray>()
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 1, payload)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 1, ByteArray(0))
        chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = 2)
        val pipe = ScriptedPipe(chunks)
        val pauses = ArrayList<Long>()
        val session = PtpSession(pipe, pause = { pauses += it })

        assertArrayEquals(jpeg, session.nextLiveViewJpeg())
        assertNull(session.nextLiveViewJpeg())

        assertEquals(listOf(10L), pauses)
        assertTrue(pipe.written.none { opcode(it) == PtpCodec.OC_SDIO_ALL_PROPS })
        assertTrue(pipe.written.none { it.size >= 16 && param(it) == PtpCodec.captureHandle })
    }

    @Test
    fun captureEventDrainsHostStillBeforeNextLiveView() {
        val still = ByteArray(48) { it.toByte() }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x22, 0xFF.toByte(), 0xD9.toByte())
        val payload = ByteBuffer.allocate(4 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)
            .put(jpeg)
            .array()
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_OK, tx = 1, data = objectInfo(still.size), op = PtpCodec.OC_GET_OBJECT_INFO)
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 2, still)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 2, ByteArray(0))
        chunks += response(PtpCodec.RC_OK, tx = 3, data = liveViewProps(1), op = PtpCodec.OC_SDIO_ALL_PROPS)
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 4, payload)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 4, ByteArray(0))
        val pipe = ScriptedPipe(chunks, interrupts = listOf(captureEvent()))
        val pauses = ArrayList<Long>()
        val session = PtpSession(pipe, pause = { pauses += it })

        assertNull(session.nextLiveViewJpeg())
        assertArrayEquals(jpeg, session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(
            listOf(
                PtpCodec.OC_GET_OBJECT_INFO,
                PtpCodec.OC_GET_OBJECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
                PtpCodec.OC_GET_OBJECT,
            ),
            commands.map(::opcode),
        )
        assertEquals(PtpCodec.captureHandle, param(commands[0]))
        assertEquals(PtpCodec.captureHandle, param(commands[1]))
        assertEquals(PtpCodec.liveViewHandle, param(commands[3]))
        assertEquals(listOf(20L), pauses)
        assertFalse(pipe.isClosed)
    }

    @Test
    fun repeatedAccessDeniedDoesNotProbeCaptureHandle() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0xFF.toByte(), 0xD9.toByte())
        val payload = ByteBuffer.allocate(4 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)
            .put(jpeg)
            .array()
        val chunks = ArrayList<ByteArray>()
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 1, payload)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 1, ByteArray(0))
        for (tx in 2..9) chunks += response(PtpCodec.RC_ACCESS_DENIED, tx = tx)
        val pipe = ScriptedPipe(chunks)
        val session = PtpSession(pipe, pause = { _ -> })

        assertArrayEquals(jpeg, session.nextLiveViewJpeg())
        repeat(8) { assertNull(session.nextLiveViewJpeg()) }

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(9, commands.size)
        assertTrue(commands.all { opcode(it) == PtpCodec.OC_GET_OBJECT && param(it) == PtpCodec.liveViewHandle })
        assertFalse(pipe.isClosed)
    }

    @Test
    fun rawPlusJpegDrainsEachInterruptOnce() {
        val raw = ByteArray(90) { it.toByte() }
        val jpegStill = ByteArray(40) { (it + 1).toByte() }
        val chunks = ArrayList<ByteArray>()
        chunks += response(PtpCodec.RC_OK, tx = 1, data = objectInfo(raw.size), op = PtpCodec.OC_GET_OBJECT_INFO)
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 2, raw)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 2, ByteArray(0))
        chunks += response(PtpCodec.RC_OK, tx = 3, op = PtpCodec.OC_SDIO_ALL_PROPS)
        chunks += response(PtpCodec.RC_OK, tx = 4, data = objectInfo(jpegStill.size), op = PtpCodec.OC_GET_OBJECT_INFO)
        chunks += PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 5, jpegStill)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 5, ByteArray(0))
        chunks += response(PtpCodec.RC_OK, tx = 6, op = PtpCodec.OC_SDIO_ALL_PROPS)
        val pipe = ScriptedPipe(chunks, interrupts = listOf(captureEvent(), captureEvent()))
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.nextLiveViewJpeg())
        assertNull(session.nextLiveViewJpeg())

        val commands = pipe.written.filter { containerType(it) == PtpCodec.TYPE_COMMAND }
        assertEquals(
            listOf(
                PtpCodec.OC_GET_OBJECT_INFO,
                PtpCodec.OC_GET_OBJECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
                PtpCodec.OC_GET_OBJECT_INFO,
                PtpCodec.OC_GET_OBJECT,
                PtpCodec.OC_SDIO_ALL_PROPS,
            ),
            commands.map(::opcode),
        )
        assertTrue(commands.filter { it.size >= 16 }.all { param(it) == PtpCodec.captureHandle })
        assertFalse(pipe.isClosed)
    }

    @Test
    fun shootingFileInfoReadsObjectInMemory() {
        assertEquals(0x8001L, PtpCodec.shootingFileInfo(memoryProps(0x8001)))
        assertEquals(0x8000L, PtpCodec.shootingFileInfo(memoryProps(0x8000)))
        assertEquals(0L, PtpCodec.shootingFileInfo(memoryProps(0)))
    }

    @Test
    fun liveViewSoftTimeoutReturnsNullWithoutTouchingCaptureHandle() {
        val pipe = ScriptedPipe(emptyList())
        val session = PtpSession(pipe, pause = { _ -> })

        assertNull(session.nextLiveViewJpeg())
        assertFalse(pipe.isClosed)
        assertTrue(pipe.written.none { param(it) == PtpCodec.captureHandle })
    }

    @Test
    fun nextFrameExtractsJpegFromSplitGetObject() {
        val jpeg = byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0x01,
            0x02,
            0x03,
            0xFF.toByte(),
            0xD9.toByte(),
        )
        val objectBytes = ByteBuffer.allocate(4 + jpeg.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4)
            .put(jpeg)
            .array()
        val data = PtpCodec.container(PtpCodec.TYPE_DATA, PtpCodec.OC_GET_OBJECT, 1, objectBytes)
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + 8, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        chunks += ByteArray(0)
        chunks += PtpCodec.container(PtpCodec.TYPE_RESPONSE, PtpCodec.RC_OK, 1, ByteArray(0))
        val session = PtpSession(ScriptedPipe(chunks), pause = { _ -> })

        assertArrayEquals(jpeg, session.nextLiveViewJpeg())
    }

    private fun captureEvent(): ByteArray {
        return PtpCodec.container(
            PtpCodec.TYPE_EVENT,
            PtpCodec.EC_SONY_OBJECT_ADDED,
            -1,
            PtpCodec.params(intArrayOf(PtpCodec.captureHandle)),
        )
    }

    private fun objectInfo(compressedSize: Int): ByteArray {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x00010001)
            .putShort(0x3801)
            .putShort(0)
            .putInt(compressedSize)
            .array()
    }

    private fun memoryProps(current: Int): ByteArray {
        val body = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        body.putLong(1)
        body.putShort(0xD215.toShort())
        body.putShort(0x0004)
        body.put(0)
        body.put(1)
        body.putShort(0)
        body.putShort(current.toShort())
        body.put(0)
        return body.array()
    }

    private fun liveViewProps(current: Int): ByteArray {
        val body = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        body.putLong(2)
        body.putShort(0xD20D.toShort())
        body.putShort(0x0004)
        body.put(0)
        body.put(1)
        body.putShort(0)
        body.putShort(0)
        body.put(0)
        body.putShort(0xD221.toShort())
        body.putShort(0x0002)
        body.put(0)
        body.put(1)
        body.put(0)
        body.put(current.toByte())
        body.put(0)
        return body.array()
    }

    private fun extInfo(): ByteArray = byteArrayOf(0xC8.toByte(), 0x00, 0x21, 0xD2.toByte())

    private fun response(code: Int, tx: Int, data: ByteArray? = null, op: Int = code): List<ByteArray> {
        val packets = ArrayList<ByteArray>(2)
        if (data != null) packets += PtpCodec.container(PtpCodec.TYPE_DATA, op, tx, data)
        packets += PtpCodec.container(PtpCodec.TYPE_RESPONSE, code, tx, ByteArray(0))
        return packets
    }

    private fun opcode(packet: ByteArray): Int = u16(packet, 6)

    private fun containerType(packet: ByteArray): Int = u16(packet, 4)

    private fun transactionId(packet: ByteArray): Int = u32(packet, 8).toInt()

    private fun param(packet: ByteArray): Int = u32(packet, 12).toInt()

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
}

private class ScriptedPipe(
    transfers: List<ByteArray>,
    interrupts: List<ByteArray> = emptyList(),
) : BulkPipe {
    private val incoming = ArrayDeque(transfers)
    private val interruptIncoming = ArrayDeque(interrupts)
    val written = ArrayList<ByteArray>()
    var reads = 0
        private set

    override val maxPacketIn: Int = 512
    override var isClosed: Boolean = false
    var interruptTimeoutMs: Int = -1
        private set

    override fun write(buffer: ByteArray, timeoutMs: Int) {
        written += buffer.copyOf()
    }

    override fun pollInterrupt(buffer: ByteArray, timeoutMs: Int): Int {
        interruptTimeoutMs = timeoutMs
        if (interruptIncoming.isEmpty()) return 0
        val chunk = interruptIncoming.removeFirst()
        if (chunk.size > buffer.size) error("scripted interrupt does not fit the read buffer")
        chunk.copyInto(buffer, 0, 0, chunk.size)
        return chunk.size
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        reads++
        if (incoming.isEmpty()) return -1
        val chunk = incoming.removeFirst()
        if (chunk.isEmpty()) return 0
        if (chunk.size > buffer.size) error("scripted transfer does not fit the read buffer")
        chunk.copyInto(buffer, 0, 0, chunk.size)
        return chunk.size
    }

    override fun recoverStall() = Unit

    override fun shutdown() {
        isClosed = true
    }
}
