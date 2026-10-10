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
 * through libaums, and write raw 512-byte sectors ourselves (MBR + FAT32).
 *
 * We open the *whole disk* (not libaums' partition objects) so blank / unsupported
 * drives also work and we can write a fresh partition table.
 */

enum class FsType(val label: String, val supported: Boolean, val note: String) {
    FAT32("FAT32", true, ""),
    EXFAT("exFAT", false, "not implemented yet"),
    NTFS("NTFS", false, "needs root"),
    EXT4("ext4", false, "needs root")
}

sealed interface FormatProgress {
    data class Message(val text: String) : FormatProgress
    data class Percent(val value: Int) : FormatProgress
    data object Done : FormatProgress
    data class Error(val reason: String) : FormatProgress
}

class UnsupportedFsException(message: String) : Exception(message)

class UsbFormatter(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _progress = MutableStateFlow<FormatProgress>(FormatProgress.Message("Idle"))
    val progress: StateFlow<FormatProgress> = _progress.asStateFlow()

    private var lastPercent = 0

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.usbformatter.USB_PERMISSION"
        const val SECTOR_SIZE = 512
        const val PARTITION_START_LBA = 2048L
    }

    /** Attached devices that expose a Mass Storage / SCSI / bulk-only interface. */
    fun listMassStorageDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { findMassStorageInterface(it) }

    private fun findMassStorageInterface(d: UsbDevice): Boolean =
        (0 until d.interfaceCount).any {
            val i = d.getInterface(it)
            i.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                i.interfaceSubclass == 6 && i.interfaceProtocol == 80
        }

    fun describe(d: UsbDevice): String {
        val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")
            .ifBlank { "USB device" }
        return "$name (${"%04x:%04x".format(d.vendorId, d.productId)})"
    }

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

    /** DESTROYS ALL DATA on [device]. Caller must have confirmed with the user. */
    suspend fun format(device: UsbDevice, fsType: FsType, volumeLabel: String) =
        withContext(Dispatchers.IO) {
            var comm: UsbCommunication? = null
            try {
                if (!fsType.supported) {
                    throw UnsupportedFsException(
                        "${fsType.label} is ${fsType.note}. Use FAT32."
                    )
                }
                if (!usbManager.hasPermission(device)) {
                    emit(FormatProgress.Error("No USB permission for this device"))
                    return@withContext
                }
                emit(FormatProgress.Message("Opening device…"))
                val (c, block) = openRaw(device)
                comm = c

                val total = block.blocks
                if (block.blockSize != SECTOR_SIZE) {
                    throw IOException("Unsupported sector size ${block.blockSize} (need 512)")
                }
                if (total > 0xFFFFFFFFL) {
                    throw IOException("Drive is larger than 2 TB; MBR/FAT32 can't address it")
                }
                emit(FormatProgress.Message("Drive: ${total * SECTOR_SIZE / (1024 * 1024)} MB"))

                emit(FormatProgress.Message("Wiping old partition tables…"))
                zeroSectors(block, 0, PARTITION_START_LBA, 0, 5)
                val tail = minOf(34L, total - PARTITION_START_LBA)
                zeroSectors(block, total - tail, tail, 5, 6)

                val partSectors = total - PARTITION_START_LBA
                emit(FormatProgress.Message("Writing partition table…"))
                block.write(0, ByteBuffer.wrap(buildMbr(PARTITION_START_LBA, partSectors)))

                Fat32Formatter(block, PARTITION_START_LBA, partSectors, ::emit).format(volumeLabel)

                emit(FormatProgress.Percent(100))
                emit(FormatProgress.Done)
            } catch (e: Exception) {
                emit(FormatProgress.Error(e.message ?: "Format failed"))
            } finally {
                runCatching { comm?.close() }
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

    private fun buildMbr(startLba: Long, sectors: Long): ByteArray {
        val b = ByteBuffer.allocate(SECTOR_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.position(446)
        b.put(0x00)                                           // not bootable
        b.put(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS start (use LBA)
        b.put(0x0C)                                           // FAT32 LBA
        b.put(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())) // CHS end
        b.putInt(startLba.toInt())
        b.putInt(sectors.toInt())
        b.position(510)
        b.put(0x55)
        b.put(0xAA.toByte())
        return b.array()
    }

    private fun zeroSectors(
        block: BlockDeviceDriver, start: Long, count: Long, pctLo: Int, pctHi: Int
    ) {
        val chunk = 256L
        val zero = ByteArray((chunk * SECTOR_SIZE).toInt())
        var done = 0L
        while (done < count) {
            val n = minOf(chunk, count - done)
            val buf = if (n == chunk) zero else ByteArray((n * SECTOR_SIZE).toInt())
            block.write(start + done, ByteBuffer.wrap(buf))
            done += n
            emit(FormatProgress.Percent(pctLo + ((pctHi - pctLo) * done / count).toInt()))
        }
    }

    internal fun emit(p: FormatProgress) {
        _progress.value = p
    }

    /** Writes a fresh FAT32 volume inside the partition that starts at [startLba]. */
    private inner class Fat32Formatter(
        private val block: BlockDeviceDriver,
        private val startLba: Long,
        private val partSectors: Long,
        private val report: (FormatProgress) -> Unit
    ) {
        private val bps = SECTOR_SIZE
        private val reserved = 32
        private val numFats = 2

        fun format(rawLabel: String) {
            val label = rawLabel.uppercase().filter { it.code in 0x20..0x7E }
                .ifBlank { "USB" }.take(11)

            val spc = sectorsPerCluster(partSectors)
            val t1 = partSectors - reserved
            val t2 = (256L * spc) + numFats
            val spf = (t1 + (t2 - 1)) / t2

            val dataStart = reserved + numFats * spf
            val clusters = (partSectors - dataStart) / spc
            if (clusters < 65525) {
                throw IOException("Drive too small for FAT32 (needs roughly 33 MB or more)")
            }

            val volumeId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

            report(FormatProgress.Message("Writing boot sector…"))
            val boot = bootSector(spc, spf, volumeId, label)
            writeSector(0, boot)
            writeSector(6, boot)

            report(FormatProgress.Message("Writing FSInfo…"))
            writeSector(1, fsInfo())
            writeSector(7, fsInfo())

            report(FormatProgress.Message("Clearing FAT tables…"))
            zeroRel(reserved.toLong(), numFats * spf, 6, 90)

            val seed = ByteBuffer.allocate(bps).order(ByteOrder.LITTLE_ENDIAN)
            seed.putInt(0x0FFFFFF8)
            seed.putInt(0x0FFFFFFF)
            seed.putInt(0x0FFFFFFF) // cluster 2 (root dir) = end of chain
            for (f in 0 until numFats) writeSector(reserved + f * spf, seed.array())

            report(FormatProgress.Message("Writing root directory…"))
            zeroRel(dataStart, spc.toLong(), 90, 98)
            val entry = ByteArray(bps)
            System.arraycopy(label.padEnd(11).toByteArray(Charsets.US_ASCII), 0, entry, 0, 11)
            entry[11] = 0x08 // volume label attribute
            writeSector(dataStart, entry)

            report(FormatProgress.Message("FAT32 format complete"))
        }

        private fun sectorsPerCluster(sectors: Long): Int {
            val mb = sectors * bps / (1024 * 1024)
            return when {
                mb <= 260 -> 1
                mb <= 8_192 -> 8
                mb <= 16_384 -> 16
                mb <= 32_768 -> 32
                else -> 64
            }
        }

        private fun bootSector(spc: Int, spf: Long, volumeId: Int, label: String): ByteArray {
            val b = ByteBuffer.allocate(bps).order(ByteOrder.LITTLE_ENDIAN)
            b.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()))
            b.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))
            b.putShort(bps.toShort())
            b.put(spc.toByte())
            b.putShort(reserved.toShort())
            b.put(numFats.toByte())
            b.putShort(0)                      // root entries
            b.putShort(0)                      // total sectors 16
            b.put(0xF8.toByte())               // media
            b.putShort(0)                      // FAT size 16
            b.putShort(63)                     // sectors/track
            b.putShort(255)                    // heads
            b.putInt(startLba.toInt())         // hidden sectors
            b.putInt(partSectors.toInt())      // total sectors 32
            b.putInt(spf.toInt())              // FAT size 32
            b.putShort(0)                      // ext flags
            b.putShort(0)                      // fs version
            b.putInt(2)                        // root cluster
            b.putShort(1)                      // FSInfo sector
            b.putShort(6)                      // backup boot sector
            b.put(ByteArray(12))
            b.put(0x80.toByte())               // drive number
            b.put(0)
            b.put(0x29)                        // extended boot signature
            b.putInt(volumeId)
            b.put(label.padEnd(11).toByteArray(Charsets.US_ASCII))
            b.put("FAT32   ".toByteArray(Charsets.US_ASCII))
            b.position(510)
            b.put(0x55)
            b.put(0xAA.toByte())
            return b.array()
        }

        private fun fsInfo(): ByteArray {
            val b = ByteBuffer.allocate(bps).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0x41615252)
            b.position(484)
            b.putInt(0x61417272)
            b.putInt(-1)                       // free clusters unknown
            b.putInt(-1)                       // next free unknown
            b.position(508)
            b.putInt(0xAA550000.toInt())
            return b.array()
        }

        /** [rel] is relative to the partition start. */
        private fun writeSector(rel: Long, data: ByteArray) {
            block.write(startLba + rel, ByteBuffer.wrap(data))
        }

        private fun writeSector(rel: Int, data: ByteArray) = writeSector(rel.toLong(), data)

        private fun zeroRel(rel: Long, count: Long, pctLo: Int, pctHi: Int) {
            zeroSectors(block, startLba + rel, count, pctLo, pctHi)
        }
    }
}
