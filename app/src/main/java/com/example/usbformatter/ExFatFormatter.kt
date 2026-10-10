package com.example.usbformatter

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a fresh exFAT filesystem, following Microsoft's exFAT specification.
 *
 * Layout (sector numbers relative to partition start):
 *   0..11   main boot region (VBR, 8 extended boot sectors, OEM params, reserved, checksum)
 *   12..23  backup boot region (identical copy)
 *   FatOffset.. FAT (single copy)
 *   ClusterHeapOffset.. cluster heap:
 *       cluster 2..        allocation bitmap
 *       next               up-case table (128 KB, uncompressed)
 *       next               root directory (volume label + bitmap + up-case entries)
 */
internal class ExFatFormatter(private val vol: RawVolume) {

    private val le = ByteOrder.LITTLE_ENDIAN

    fun format(rawLabel: String) {
        val label = rawLabel
            .filter { !it.isISOControl() && it !in "\"*/:<>?\\|" }
            .ifBlank { "USB" }
            .take(11)

        val total = vol.sectors
        val mb = total * SECTOR / (1024 * 1024)
        val spcShift = when {
            mb <= 256 -> 3      // 4 KB clusters
            mb <= 32_768 -> 6   // 32 KB clusters
            else -> 8           // 128 KB clusters
        }
        val spc = 1L shl spcShift
        val clusterBytes = spc * SECTOR

        val fatOffset = 128L
        // Slight over-estimate of the FAT size is allowed by the spec.
        val fatLength = ceilDiv((total / spc + 2) * 4, SECTOR.toLong())
        // Align the cluster heap to 4 MB on the physical disk (flash friendly).
        val absHeap = alignUp(vol.startLba + fatOffset + fatLength, 8192L)
        val heapOffset = absHeap - vol.startLba
        val clusterCount = (total - heapOffset) / spc
        if (clusterCount < 1024) {
            throw IOException("Drive too small for exFAT (needs roughly 64 MB or more)")
        }
        if (clusterCount > 0xFFFFFFF5L) {
            throw IOException("Drive too large for exFAT cluster addressing")
        }

        val bitmapBytes = ceilDiv(clusterCount, 8)
        val bitmapClusters = ceilDiv(bitmapBytes, clusterBytes)
        val upcase = buildUpcaseTable()
        val upcaseClusters = ceilDiv(upcase.size.toLong(), clusterBytes)
        val bitmapFirst = 2L
        val upcaseFirst = bitmapFirst + bitmapClusters
        val rootFirst = upcaseFirst + upcaseClusters
        val usedClusters = bitmapClusters + upcaseClusters + 1

        fun clusterSector(c: Long) = heapOffset + (c - 2) * spc

        // ---- boot regions -------------------------------------------------
        vol.message("Writing exFAT boot regions…")
        val serial = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        val region = ByteArray(12 * SECTOR)
        val vbr = buildVbr(
            total, fatOffset, fatLength, heapOffset, clusterCount, rootFirst, serial, spcShift
        )
        System.arraycopy(vbr, 0, region, 0, SECTOR)
        for (i in 1..8) { // extended boot sectors: signature only
            region[i * SECTOR + 510] = 0x55
            region[i * SECTOR + 511] = 0xAA.toByte()
        }
        val checksum = checksum32(region, 11 * SECTOR, setOf(106, 107, 112))
        val csBuf = ByteBuffer.wrap(region).order(le)
        for (j in 0 until SECTOR / 4) csBuf.putInt(11 * SECTOR + j * 4, checksum)
        vol.write(0, region)
        vol.write(12, region)
        vol.percent(6)

        // ---- FAT ----------------------------------------------------------
        vol.message("Clearing FAT…")
        vol.zero(fatOffset, fatLength, 6, 85)

        vol.message("Writing FAT chains…")
        val fatBytes = ceilDiv((rootFirst + 1) * 4, SECTOR.toLong()) * SECTOR
        val fat = ByteBuffer.allocate(fatBytes.toInt()).order(le)
        fat.putInt(0, 0xFFFFFFF8.toInt())
        fat.putInt(4, -1)
        fun chain(first: Long, count: Long) {
            for (i in 0 until count) {
                val c = first + i
                val next = if (i == count - 1) -1 else (c + 1).toInt()
                fat.putInt((c * 4).toInt(), next)
            }
        }
        chain(bitmapFirst, bitmapClusters)
        chain(upcaseFirst, upcaseClusters)
        chain(rootFirst, 1)
        vol.write(fatOffset, fat.array())
        vol.percent(88)

        // ---- allocation bitmap -------------------------------------------
        vol.message("Writing allocation bitmap…")
        val bitmap = ByteArray((bitmapClusters * clusterBytes).toInt())
        for (i in 0 until usedClusters.toInt()) {
            bitmap[i / 8] = (bitmap[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        vol.write(clusterSector(bitmapFirst), bitmap)
        vol.percent(92)

        // ---- up-case table -------------------------------------------------
        vol.message("Writing up-case table…")
        val upcaseChecksum = checksum32(upcase, upcase.size, emptySet())
        val upcasePadded = upcase.copyOf((upcaseClusters * clusterBytes).toInt())
        vol.write(clusterSector(upcaseFirst), upcasePadded)
        vol.percent(96)

        // ---- root directory -------------------------------------------------
        vol.message("Writing root directory…")
        val root = ByteArray(clusterBytes.toInt())
        val rb = ByteBuffer.wrap(root).order(le)
        // Entry 0: volume label
        root[0] = 0x83.toByte()
        root[1] = label.length.toByte()
        label.toByteArray(Charsets.UTF_16LE).copyInto(root, 2)
        // Entry 1: allocation bitmap
        root[32] = 0x81.toByte()
        root[33] = 0
        rb.putInt(32 + 20, bitmapFirst.toInt())
        rb.putLong(32 + 24, bitmapBytes)
        // Entry 2: up-case table
        root[64] = 0x82.toByte()
        rb.putInt(64 + 4, upcaseChecksum)
        rb.putInt(64 + 20, upcaseFirst.toInt())
        rb.putLong(64 + 24, upcase.size.toLong())
        vol.write(clusterSector(rootFirst), root)

        vol.message("exFAT format complete")
    }

    private fun buildVbr(
        total: Long, fatOffset: Long, fatLength: Long, heapOffset: Long,
        clusterCount: Long, rootFirst: Long, serial: Int, spcShift: Int
    ): ByteArray {
        val b = ByteBuffer.allocate(SECTOR).order(le)
        b.put(0xEB.toByte()); b.put(0x76); b.put(0x90.toByte())     // jump
        b.put("EXFAT   ".toByteArray(Charsets.US_ASCII))            // 8 bytes
        b.position(64)                                              // 53 zero bytes before
        b.putLong(vol.startLba)                                     // partition offset
        b.putLong(total)                                            // volume length
        b.putInt(fatOffset.toInt())
        b.putInt(fatLength.toInt())
        b.putInt(heapOffset.toInt())
        b.putInt(clusterCount.toInt())
        b.putInt(rootFirst.toInt())
        b.putInt(serial)
        b.putShort(0x0100)                                          // revision 1.00
        b.putShort(0)                                               // volume flags
        b.put(9)                                                    // bytes/sector = 512
        b.put(spcShift.toByte())
        b.put(1)                                                    // number of FATs
        b.put(0x80.toByte())                                        // drive select
        b.put(0xFF.toByte())                                        // percent in use: unknown
        for (i in 120 until 510) b.put(i, 0xF4.toByte())            // boot code filler
        b.put(510, 0x55)
        b.put(511, 0xAA.toByte())
        return b.array()
    }

    /** Full 65536-entry up-case table (uncompressed, 128 KB). */
    private fun buildUpcaseTable(): ByteArray {
        val bb = ByteBuffer.allocate(65536 * 2).order(le)
        for (c in 0 until 65536) {
            var u = Character.toUpperCase(c.toChar()).code
            // Keep non-ASCII characters from collapsing into ASCII (e.g. dotless i, long s).
            if (c >= 0x80 && u < 0x80) u = c
            bb.putShort(u.toShort())
        }
        return bb.array()
    }

    /** exFAT checksum: rotate right by one, then add the byte. */
    private fun checksum32(data: ByteArray, len: Int, skip: Set<Int>): Int {
        var sum = 0
        for (i in 0 until len) {
            if (i in skip) continue
            val rot = (if ((sum and 1) != 0) Int.MIN_VALUE else 0) + (sum ushr 1)
            sum = rot + (data[i].toInt() and 0xFF)
        }
        return sum
    }

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

    private fun alignUp(v: Long, a: Long) = ceilDiv(v, a) * a
}
