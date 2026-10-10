package com.example.usbformatter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root-free USB formatter.
 *
 * Stock Android has no "format" API and SAF cannot format. The root-free route is the
 * USB Host API: claim the device, speak USB Mass Storage (Bulk-Only Transport) + SCSI
 * through libaums, and write raw 512-byte sectors ourselves (MBR + FAT32 / exFAT).
 *
 * We open the whole disk (not libaums' partition objects) so blank or unrecognised
 * drives work too and we can write a fresh partition table.
 */

enum class FsType(val label: String, val tagline: String, val mbrType: Int) {
    FAT32("FAT32", "Works on almost everything. Files up to 4 GB.", 0x0C),
    EXFAT("exFAT", "Big files and big drives. Modern devices.", 0x07)
}

sealed interface FormatProgress {
    data class Message(val text: String) : FormatProgress
    data class Percent(val value: Int) : FormatProgress
    data object Done : FormatProgress
    data class Error(val reason: String) : FormatProgress
}

enum class Phase { Idle, Running, Done, Error }

data class FormatState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val percent: Int = 0
)

class UsbFormatter(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _state = MutableStateFlow(FormatState())
    val state: StateFlow<FormatState> = _state.asStateFlow()

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.usbformatter.USB_PERMISSION"
        const val PARTITION_START_LBA = 2048L
    }

    /** Attached devices that expose a Mass Storage / SCSI / bulk-only interface. */
    fun listMassStorageDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any {
                val i = dev.getInterface(it)
                i.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    i.interfaceSubclass == 6 && i.interfaceProtocol == 80
            }
        }.sortedBy { it.deviceName }

    fun displayName(d: UsbDevice): String {
        val n = runCatching {
            listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")
        }.getOrDefault("")
        return n.ifBlank { "USB drive" }
    }

    fun displayId(d: UsbDevice): String =
        "ID %04x:%04x".format(d.vendorId, d.productId)

    fun fail(reason: String) = emit(FormatProgress.Error(reason))

    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        if (usbManager.hasPermission(device)) {
            onResult(true)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                appContext.unregisterReceiver(this)
                onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, pi)
    }

    /** DESTROYS ALL DATA on [device]. */
    suspend fun format(device: UsbDevice, fsType: FsType, volumeLabel: String) {
        withContext(Dispatchers.IO) {
            _state.value = FormatState(Phase.Running, "Opening drive…", 0)
            var comm: UsbCommunication? = null
            try {
                if (!usbManager.hasPermission(device)) throw IOException("No USB permission for this drive")
                val (c, block) = openRaw(device)
                comm = c

                val total = block.blocks
                if (block.blockSize != SECTOR) {
                    throw IOException("Unsupported sector size ${block.blockSize} (need 512)")
                }
                if (total > 0xFFFFFFFFL) {
                    throw IOException("Drive is larger than 2 TB; MBR can't address it")
                }
                if (total < PARTITION_START_LBA + 4096) {
                    throw IOException("Drive is too small to format")
                }
                emit(FormatProgress.Message("Drive size: ${total * SECTOR / (1024 * 1024)} MB"))

                emit(FormatProgress.Message("Wiping old partition tables…"))
                zeroSectors(block, 0, PARTITION_START_LBA, 0, 3, ::emit)
                val tail = minOf(34L, total - PARTITION_START_LBA)
                zeroSectors(block, total - tail, tail, 3, 4, ::emit)

                val partSectors = total - PARTITION_START_LBA
                emit(FormatProgress.Message("Writing partition table…"))
                block.write(0, ByteBuffer.wrap(buildMbr(PARTITION_START_LBA, partSectors, fsType.mbrType)))

                val vol = RawVolume(block, PARTITION_START_LBA, partSectors, ::emit)
                when (fsType) {
                    FsType.FAT32 -> Fat32Formatter(vol).format(volumeLabel)
                    FsType.EXFAT -> ExFatFormatter(vol).format(volumeLabel)
                }
                emit(FormatProgress.Done)
            } catch (e: Exception) {
                emit(FormatProgress.Error(e.message ?: "Format failed"))
            } finally {
                runCatching { comm?.close() }
            }
        }
    }

    private fun openRaw(device: UsbDevice): Pair<UsbCommunication, BlockDeviceDriver> {
        for (idx in 0 until device.interfaceCount) {
            val intf = device.getInterface(idx)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE ||
                intf.interfaceSubclass != 6 || intf.interfaceProtocol != 80
            ) continue

            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep else inn = ep
                }
            }
            if (out == null || inn == null) continue

            val comm = UsbCommunicationFactory.createUsbCommunication(
                usbManager, device, intf, out, inn
            )
            // GET MAX LUN; many devices ignore it, so failures are fine.
            runCatching { comm.controlTransfer(161, 254, 0, intf.id, ByteArray(1), 1) }
            val block = BlockDeviceDriverFactory.createBlockDevice(comm, lun = 0.toByte())
            block.init()
            return comm to block
        }
        throw IOException("No usable mass-storage interface found")
    }

    private fun buildMbr(startLba: Long, sectors: Long, type: Int): ByteArray {
        val b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        b.position(446)
        b.put(0x00)                                                     // not bootable
        b.put(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS start (use LBA)
        b.put(type.toByte())
        b.put(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS end
        b.putInt(startLba.toInt())
        b.putInt(sectors.toInt())
        b.position(510)
        b.put(0x55)
        b.put(0xAA.toByte())
        return b.array()
    }

    internal fun emit(p: FormatProgress) {
        _state.update { s ->
            when (p) {
                is FormatProgress.Message -> s.copy(message = p.text)
                is FormatProgress.Percent -> s.copy(percent = p.value.coerceIn(0, 100))
                FormatProgress.Done -> FormatState(Phase.Done, "Done. Safe to remove the drive.", 100)
                is FormatProgress.Error -> s.copy(phase = Phase.Error, message = p.reason)
            }
        }
    }
}
