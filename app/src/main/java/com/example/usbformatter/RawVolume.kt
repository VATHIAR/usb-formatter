package com.example.usbformatter

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

internal const val SECTOR = 512

/** Writes zeros to [count] sectors starting at absolute sector [start], reporting progress. */
internal fun zeroSectors(
    block: BlockDeviceDriver,
    start: Long,
    count: Long,
    pctLo: Int,
    pctHi: Int,
    report: (FormatProgress) -> Unit
) {
    if (count <= 0) return
    val chunk = 256L // 128 KB per write
    val zero = ByteArray((chunk * SECTOR).toInt())
    var done = 0L
    var lastPct = -1
    while (done < count) {
        val n = minOf(chunk, count - done)
        val buf = if (n == chunk) zero else ByteArray((n * SECTOR).toInt())
        block.write(start + done, ByteBuffer.wrap(buf))
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
    val block: BlockDeviceDriver,
    val startLba: Long,
    val sectors: Long,
    private val report: (FormatProgress) -> Unit
) {
    fun message(text: String) = report(FormatProgress.Message(text))

    fun percent(value: Int) = report(FormatProgress.Percent(value))

    /** Writes [data] (a multiple of 512 bytes) at relative sector [relSector]. */
    fun write(relSector: Long, data: ByteArray) {
        require(data.size % SECTOR == 0) { "write size must be a multiple of $SECTOR" }
        val chunk = 128 * 1024
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            val part = data.copyOfRange(off, off + n)
            block.write(startLba + relSector + off / SECTOR, ByteBuffer.wrap(part))
            off += n
        }
    }

    fun zero(relStart: Long, count: Long, pctLo: Int, pctHi: Int) =
        zeroSectors(block, startLba + relStart, count, pctLo, pctHi, report)
}
