package com.emma.duplicates.core.storage

import android.graphics.Color
import android.graphics.ImageDecoder
import com.emma.duplicates.core.database.ToolItemEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

class SimilarPhotoScanner(
    private val metadata: FileMetadataReader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun scan(
        discovered: ToolDiscoveryResult,
        onProgress: suspend (Int) -> Unit = {},
    ): ToolDiscoveryResult =
        withContext(ioDispatcher) {
            val images = mutableListOf<ToolItemEntity>()
            val favorites = mutableSetOf<String>()
            var skipped = discovered.skippedCount
            discovered.items.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                val fingerprinted = fingerprintPhoto(item)
                if (fingerprinted == null) {
                    skipped++
                } else {
                    images.add(fingerprinted)
                    if (metadata.read(File(item.canonicalPath)).isFavorite == true) favorites.add(item.id)
                }
                onProgress(index + 1)
            }
            discovered.copy(items = groupSimilarPhotos(images, favorites).flatten(), skippedCount = skipped)
        }
}

// ImageDecoder applies encoded orientation before reporting dimensions and sampling.
internal fun fingerprintPhoto(item: ToolItemEntity): ToolItemEntity? =
    try {
        var width = 0
        var height = 0
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(item.canonicalPath))) { decoder, info, _ ->
                if (info.isAnimated || info.size.width <= 0 ||
                    info.size.height <= 0
                ) {
                    throw IOException("Unsupported image")
                }
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(HASH_WIDTH * SAMPLE_SCALE, HASH_HEIGHT * SAMPLE_SCALE)
                decoder.setOnPartialImageListener { false }
            }
        try {
            val hash = sampledHash(bitmap)
            hash?.let {
                item.copy(
                    width = width,
                    height = height,
                    fingerprint = it,
                    fingerprintVersion = PHOTO_HASH_VERSION,
                )
            }
        } finally {
            bitmap.recycle()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

// Area averages stabilize the 9 x 8 sample across JPEG decoder sampling boundaries.
private fun sampledHash(bitmap: android.graphics.Bitmap): Long? {
    val width = HASH_WIDTH * SAMPLE_SCALE
    val pixels = IntArray(width * HASH_HEIGHT * SAMPLE_SCALE)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, HASH_HEIGHT * SAMPLE_SCALE)
    val samples =
        IntArray(HASH_WIDTH * HASH_HEIGHT) { index ->
            var sum = 0
            for (dy in 0 until SAMPLE_SCALE) {
                for (dx in 0 until SAMPLE_SCALE) {
                    val x = index % HASH_WIDTH * SAMPLE_SCALE + dx
                    val y = index / HASH_WIDTH * SAMPLE_SCALE + dy
                    sum += luminance(pixels[y * width + x])
                }
            }
            sum / (SAMPLE_SCALE * SAMPLE_SCALE)
        }
    val hash =
        (0 until HASH_BITS).fold(0L) { bits, index ->
            val offset = index / HASH_HEIGHT * HASH_WIDTH + index % HASH_HEIGHT
            if (samples[offset] > samples[offset + 1]) bits or (1L shl index) else bits
        }
    return hash.takeIf {
        samples.max() - samples.min() >= MIN_SAMPLE_CONTRAST &&
            java.lang.Long.bitCount(hash) in MIN_HASH_DETAIL..(HASH_BITS - MIN_HASH_DETAIL)
    }
}

private fun luminance(pixel: Int): Int {
    val gray =
        (LUMA_RED * Color.red(pixel) + LUMA_GREEN * Color.green(pixel) + LUMA_BLUE * Color.blue(pixel)) / LUMA_SCALE
    return (gray * Color.alpha(pixel) + COLOR_MAX * (COLOR_MAX - Color.alpha(pixel))) / COLOR_MAX
}

internal fun photoMatch(
    left: ToolItemEntity,
    right: ToolItemEntity,
): Boolean {
    val valid = listOf(left.width, left.height, right.width, right.height).all { it != null && it > 0 }
    if (!valid || left.fingerprint == null || right.fingerprint == null) return false
    val leftRatio = requireNotNull(left.width).toDouble() / requireNotNull(left.height)
    val rightRatio = requireNotNull(right.width).toDouble() / requireNotNull(right.height)
    return left.fingerprintVersion == right.fingerprintVersion &&
        java.lang.Long.bitCount(left.fingerprint xor right.fingerprint) <= MAX_HASH_DISTANCE &&
        abs(leftRatio - rightRatio) / max(leftRatio, rightRatio) <= MAX_RATIO_DIFFERENCE
}

internal suspend fun groupSimilarPhotos(
    images: List<ToolItemEntity>,
    favorites: Set<String> = emptySet(),
): List<List<ToolItemEntity>> {
    val groups = mutableListOf<MutableList<ToolItemEntity>>()
    val bands = List(BAND_COUNT) { mutableMapOf<Long, MutableSet<Int>>() }
    images.sortedWith(compareBy(ToolItemEntity::canonicalPath, ToolItemEntity::volume)).forEach { item ->
        currentCoroutineContext().ensureActive()
        val hash = item.fingerprint ?: return@forEach
        val candidates = sortedSetOf<Int>()
        for (band in 0 until BAND_COUNT) bands[band][bandValue(hash, band)]?.let(candidates::addAll)
        var groupIndex: Int? = null
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            if (groups[candidate].all { photoMatch(item, it) }) {
                groupIndex = candidate
                break
            }
        }
        val destination = groupIndex ?: groups.size.also { groups.add(mutableListOf()) }
        groups[destination].add(item)
        for (band in 0 until BAND_COUNT) bands[band].getOrPut(bandValue(hash, band)) { mutableSetOf() }.add(destination)
    }
    return groups.filter { it.size > 1 }.map { members ->
        val keeper =
            members
                .sortedWith(
                    compareByDescending<ToolItemEntity> { it.id in favorites }
                        .thenByDescending { (it.width ?: 0).toLong() * (it.height ?: 0) }
                        .thenByDescending { it.sizeBytes }
                        .thenBy { it.canonicalPath }
                        .thenBy { it.volume },
                ).first()
        members.map { it.copy(groupId = members.first().id, keeper = it.id == keeper.id, selected = false) }
    }
}

private fun bandValue(
    hash: Long,
    band: Int,
): Long =
    if (band == BAND_COUNT - 1) {
        (hash ushr (band * BAND_BITS)) and LAST_BAND_MASK
    } else {
        (hash ushr (band * BAND_BITS)) and BAND_MASK
    }

private const val HASH_WIDTH = 9
private const val HASH_HEIGHT = 8
private const val HASH_BITS = 64
private const val SAMPLE_SCALE = 8
private const val MIN_SAMPLE_CONTRAST = 20
private const val MIN_HASH_DETAIL = 4
internal const val MAX_HASH_DISTANCE = 6
internal const val MAX_RATIO_DIFFERENCE = 0.05
internal const val PHOTO_HASH_VERSION = 1
private const val BAND_COUNT = 7
private const val BAND_BITS = 9
private const val BAND_MASK = 511L
private const val LAST_BAND_MASK = 1023L
private const val LUMA_RED = 299
private const val LUMA_GREEN = 587
private const val LUMA_BLUE = 114
private const val LUMA_SCALE = 1000
private const val COLOR_MAX = 255
