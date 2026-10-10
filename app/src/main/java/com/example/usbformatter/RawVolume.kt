package com.example.usbformatter

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

internal const val SECTOR = 512

/**
 * Writes sectors to the raw disk in small pieces.
 *
 * libaums sends each write as a single USB transfer with no splitting, and many drives or
 * Android kernels reject big transfers (surfacing as "MAX_RECOVERY_ATTEMPTS exceeded").
 * We start at 16 KB per transfer and shrink automatically (to 4 KB, 1 KB, then one sector)
 * whenever a write fails, retrying the same piece.
 */
internal class DiskWriter(private val block: BlockDeviceDriver) {
    private var chunkSectors = 32 // 16 KB

    /** [absSector] is an absolute sector on the disk; [data] must be a multiple of 512 bytes. */
    fun write(absSector: Long, data: ByteArray) {
        require(data.size % SECTOR == 0) { "write size must be a multiple of $SECTOR" }
        val total = data.size / SECTOR
        var done = 0
        var failures = 0
        while (done < total) {
            val n = minOf(chunkSectors, total - done)
            val part = data.copyOfRange(done * SECTOR, (done + n) * SECTOR)
            try {
                block.write(absSector + done, ByteBuffer.wrap(part))
                done += n
                failures = 0
            } catch (e: IOException) {
                if (chunkSectors > 1) {
                    chunkSectors = maxOf(1, chunkSectors / 4)
                    Thread.sleep(300)
                } else if (++failures >= 3) {
                    throw e
                } else {
                    Thread.sleep(500)
                }
            }
        }
    }
}

/** Writes zeros to [count] sectors starting at absolute sector [start], reporting progress. */
internal fun zeroSectors(
    writer: DiskWriter,
    start: Long,
    count: Long,
    pctLo: Int,
    pctHi: Int,
    report: (FormatProgress) -> Unit
) {
    if (count <= 0) return
    val chunk = 256L // 128 KB handed to the writer, which splits it further
    val zero = ByteArray((chunk * SECTOR).toInt())
    var done = 0L
    var lastPct = -1
    while (done < count) {
        val n = minOf(chunk, count - done)
        val buf = if (n == chunk) zero else ByteArray((n * SECTOR).toInt())
        writer.write(start + done, buf)
        done += n
        val pct = pctLo + ((pctHi - pctLo) * done / count).toInt()
        if (pct != lastPct) {
            lastPct = pct
            report(FormatProgress.Percent(pct))
        }
    }
}

/** A partition on the raw disk. All sector numbers passed in are relative to its start. */
internal class RawVolume(
    private val writer: DiskWriter,
    val startLba: Long,
    val sectors: Long,
    private val report: (FormatProgress) -> Unit
) {
    fun message(text: String) = report(FormatProgress.Message(text))

    fun percent(value: Int) = report(FormatProgress.Percent(value))

    /** Writes [data] (a multiple of 512 bytes) at relative sector [relSector]. */
    fun write(relSector: Long, data: ByteArray) = writer.write(startLba + relSector, data)

    fun zero(relStart: Long, count: Long, pctLo: Int, pctHi: Int) =
        zeroSectors(writer, startLba + relStart, count, pctLo, pctHi, report)
}
