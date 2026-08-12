package com.emma.duplicates.core.format

import android.content.res.Resources
import androidx.annotation.StringRes
import com.emma.duplicates.R
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DisplayFormatters {
    private const val KILOBYTE = 1_000L
    private const val MEGABYTE = 1_000_000L
    private const val GIGABYTE = 1_000_000_000L
    private const val TERABYTE = 1_000_000_000_000L

    fun size(
        resources: Resources,
        bytes: Long,
    ): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        return when {
            safeBytes < KILOBYTE ->
                resources.getQuantityString(R.plurals.size_bytes, safeBytes.toInt(), safeBytes)
            safeBytes < MEGABYTE ->
                resources.getString(R.string.size_kilobytes, decimal(safeBytes.toDouble() / KILOBYTE))
            safeBytes < GIGABYTE ->
                resources.getString(R.string.size_megabytes, decimal(safeBytes.toDouble() / MEGABYTE))
            safeBytes < TERABYTE ->
                resources.getString(R.string.size_gigabytes, decimal(safeBytes.toDouble() / GIGABYTE))
            else ->
                resources.getString(R.string.size_terabytes, decimal(safeBytes.toDouble() / TERABYTE))
        }
    }

    fun dateTime(
        resources: Resources,
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String =
        DateTimeFormatter
            .ofPattern(resources.getString(R.string.date_time_pattern), Locale.ENGLISH)
            .withZone(zoneId)
            .format(Instant.ofEpochMilli(epochMillis))

    fun storageLabels(
        resources: Resources,
        storage: StorageSegments,
    ): StorageLabels {
        val (divisor, formatResource) =
            when {
                storage.totalBytes >= TERABYTE -> TERABYTE to R.string.size_terabytes
                storage.totalBytes >= GIGABYTE -> GIGABYTE to R.string.size_gigabytes
                storage.totalBytes >= MEGABYTE -> MEGABYTE to R.string.size_megabytes
                storage.totalBytes >= KILOBYTE -> KILOBYTE to R.string.size_kilobytes
                else ->
                    return StorageLabels(
                        total = size(resources, storage.totalBytes),
                        otherUsed = size(resources, storage.otherUsedBytes),
                        reclaimable = size(resources, storage.reclaimableBytes),
                        free = size(resources, storage.freeBytes),
                    )
            }
        val totalTenths = roundedTenths(storage.totalBytes, divisor)
        val reclaimableTenths = roundedTenths(storage.reclaimableBytes, divisor).coerceAtMost(totalTenths)
        val freeTenths =
            roundedTenths(storage.freeBytes, divisor)
                .coerceAtMost(totalTenths - reclaimableTenths)
        val otherUsedTenths = totalTenths - reclaimableTenths - freeTenths

        return StorageLabels(
            total = formatTenths(resources, totalTenths, formatResource),
            otherUsed = formatTenths(resources, otherUsedTenths, formatResource),
            reclaimable = formatTenths(resources, reclaimableTenths, formatResource),
            free = formatTenths(resources, freeTenths, formatResource),
        )
    }

    private fun decimal(value: Double): String =
        DecimalFormat(
            "0.#",
            DecimalFormatSymbols.getInstance(Locale.ENGLISH),
        ).format(value)

    private fun floorTenths(
        bytes: Long,
        divisor: Long,
    ): Long = bytes / divisor * 10L + bytes % divisor * 10L / divisor

    private fun tenthRemainder(
        bytes: Long,
        divisor: Long,
    ): Long = bytes % divisor * 10L % divisor

    private fun roundedTenths(
        bytes: Long,
        divisor: Long,
    ): Long {
        val floor = floorTenths(bytes, divisor)
        val remainder = tenthRemainder(bytes, divisor)
        return when {
            remainder * 2L > divisor -> floor + 1L
            remainder * 2L < divisor -> floor
            floor % 2L != 0L -> floor + 1L
            else -> floor
        }
    }

    private fun formatTenths(
        resources: Resources,
        tenths: Long,
        @StringRes formatResource: Int,
    ): String = resources.getString(formatResource, decimal(tenths.toDouble() / 10.0))
}

data class StorageLabels(
    val total: String,
    val otherUsed: String,
    val reclaimable: String,
    val free: String,
)

data class StorageSegments(
    val totalBytes: Long,
    val otherUsedBytes: Long,
    val reclaimableBytes: Long,
    val freeBytes: Long,
) {
    companion object {
        fun calculate(
            totalBytes: Long,
            freeBytes: Long,
            reclaimableBytes: Long,
        ): StorageSegments {
            val total = totalBytes.coerceAtLeast(0L)
            val free = freeBytes.coerceIn(0L, total)
            val used = total - free
            val reclaimable = reclaimableBytes.coerceIn(0L, used)
            return StorageSegments(
                totalBytes = total,
                otherUsedBytes = used - reclaimable,
                reclaimableBytes = reclaimable,
                freeBytes = free,
            )
        }
    }
}
