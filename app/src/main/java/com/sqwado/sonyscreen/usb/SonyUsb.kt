package com.sqwado.sonyscreen.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.sqwado.sonyscreen.ptp.BulkPipe
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB host access for a Sony body in PC Remote mode (still-image class 6/1/1).
 */
object SonyUsb {
    const val VENDOR_ID = 0x054C

    fun matches(device: UsbDevice): Boolean {
        if (device.vendorId != VENDOR_ID) return false
        return ptpInterface(device) != null
    }

    fun open(manager: UsbManager, device: UsbDevice): SonyConnection {
        val iface = ptpInterface(device)
            ?: throw IllegalStateException("Sony device has no PTP interface.")
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        var interruptIn: UsbEndpoint? = null
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            when (endpoint.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) bulkOut = endpoint
                }
                UsbConstants.USB_ENDPOINT_XFER_INT -> {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) interruptIn = endpoint
                }
            }
        }
        val input = bulkIn ?: throw IllegalStateException("PTP bulk IN endpoint is missing.")
        val output = bulkOut ?: throw IllegalStateException("PTP bulk OUT endpoint is missing.")
        val connection = manager.openDevice(device)
            ?: throw IllegalStateException("UsbManager.openDevice returned null.")
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IllegalStateException("PTP interface is already claimed.")
        }
        return SonyConnection(connection, iface, input, output, interruptIn)
    }

    private fun ptpInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val iface = device.getInterface(index)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                iface.interfaceSubclass == 1 &&
                iface.interfaceProtocol == 1
            ) {
                return iface
            }
        }
        return null
    }
}

class SonyConnection(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interruptIn: UsbEndpoint?,
) {
    val pipe: BulkPipe = UsbBulkPipe()

    private inner class UsbBulkPipe : BulkPipe {
        private val closed = AtomicBoolean(false)

        override val maxPacketIn: Int = bulkIn.maxPacketSize.coerceAtLeast(64)
        override val isClosed: Boolean
            get() = closed.get()

        override fun write(buffer: ByteArray, timeoutMs: Int) {
            if (closed.get()) throw IllegalStateException("USB connection is closed.")
            var offset = 0
            while (offset < buffer.size) {
                val written = connection.bulkTransfer(
                    bulkOut,
                    buffer,
                    offset,
                    buffer.size - offset,
                    timeoutMs,
                )
                if (written <= 0) throw IllegalStateException("USB write failed ($written).")
                offset += written
            }
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (closed.get()) return -1
            return try {
                connection.bulkTransfer(bulkIn, buffer, 0, buffer.size, timeoutMs)
            } catch (_: Exception) {
                -1
            }
        }

        override fun pollInterrupt(buffer: ByteArray, timeoutMs: Int): Int {
            val endpoint = interruptIn ?: return 0
            if (closed.get()) return -1
            return try {
                connection.bulkTransfer(endpoint, buffer, 0, buffer.size, timeoutMs)
            } catch (_: Exception) {
                -1
            }
        }

        override fun recoverStall() {
            if (closed.get()) return
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIPIENT_ENDPOINT,
                CLEAR_FEATURE,
                ENDPOINT_HALT,
                bulkIn.address,
                null,
                0,
                200,
            )
        }

        override fun shutdown() {
            if (!closed.compareAndSet(false, true)) return
            try {
                connection.close()
            } catch (_: Exception) {
            }
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val USB_RECIPIENT_ENDPOINT = 0x02
        const val CLEAR_FEATURE = 0x01
        const val ENDPOINT_HALT = 0x00
    }
}
