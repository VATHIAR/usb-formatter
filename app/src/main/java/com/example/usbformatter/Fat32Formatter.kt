package com.example.usbformatter

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a fresh FAT32 filesystem.
 *
 * Layout (relative to partition start):
 *   [reserved 32 sectors: boot, FSInfo, backups] [FAT #1] [FAT #2] [data; cluster 2 = root dir]
 */
internal class Fat32Formatter(private val vol: RawVolume) {

    private val reserved = 32
    private val numFats = 2

    fun format(rawLabel: String) {
        val label = rawLabel.uppercase()
            .filter { it.code in 0x20..0x7E && it !in "\"*+,./:;<=>?[\\]|" }
            .ifBlank { "USB" }
            .take(11)

        val total = vol.sectors
        val spc = sectorsPerCluster(total)
        val t1 = total - reserved
        val t2 = (256L * spc) + numFats
        val spf = (t1 + (t2 - 1)) / t2

        val dataStart = reserved + numFats * spf
        val clusters = (total - dataStart) / spc
        if (clusters < 65525) {
            throw IOException("Drive too small for FAT32 (needs roughly 33 MB or more). Try exFAT.")
        }

        val volumeId = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

        vol.message("Writing FAT32 boot sector…")
        val boot = bootSector(spc, spf, volumeId, label)
        vol.write(0, boot)
        vol.write(6, boot)

        vol.message("Writing FSInfo…")
        vol.write(1, fsInfo())
        vol.write(7, fsInfo())

        vol.message("Clearing FAT tables…")
        vol.zero(reserved.toLong(), numFats * spf, 6, 90)

        val seed = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        seed.putInt(0x0FFFFFF8)
        seed.putInt(0x0FFFFFFF)
        seed.putInt(0x0FFFFFFF) // cluster 2 (root dir) = end of chain
        for (f in 0 until numFats) vol.write(reserved + f * spf, seed.array())

        vol.message("Writing root directory…")
        vol.zero(dataStart, spc.toLong(), 90, 98)
        val entry = ByteArray(SECTOR)
        System.arraycopy(label.padEnd(11).toByteArray(Charsets.US_ASCII), 0, entry, 0, 11)
        entry[11] = 0x08 // volume label attribute
        vol.write(dataStart, entry)

        vol.message("FAT32 format complete")
    }

    private fun sectorsPerCluster(sectors: Long): Int {
        val mb = sectors * SECTOR / (1024 * 1024)
        return when {
            mb <= 260 -> 1
            mb <= 8_192 -> 8
            mb <= 16_384 -> 16
            mb <= 32_768 -> 32
            else -> 64
        }
    }

    private fun bootSector(spc: Int, spf: Long, volumeId: Int, label: String): ByteArray {
        val b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0xEB.toByte(), 0x58, 0x90.toByte()))
        b.put("MSWIN4.1".toByteArray(Charsets.US_ASCII))
        b.putShort(SECTOR.toShort())
        b.put(spc.toByte())
        b.putShort(reserved.toShort())
        b.put(numFats.toByte())
        b.putShort(0)                      // root entries
        b.putShort(0)                      // total sectors 16
        b.put(0xF8.toByte())               // media
        b.putShort(0)                      // FAT size 16
        b.putShort(63)                     // sectors/track
        b.putShort(255)                    // heads
        b.putInt(vol.startLba.toInt())     // hidden sectors
        b.putInt(vol.sectors.toInt())      // total sectors 32
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
        val b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x41615252)
        b.position(484)
        b.putInt(0x61417272)
        b.putInt(-1)                       // free clusters unknown
        b.putInt(-1)                       // next free unknown
        b.position(508)
        b.putInt(0xAA550000.toInt())
        return b.array()
    }
}
