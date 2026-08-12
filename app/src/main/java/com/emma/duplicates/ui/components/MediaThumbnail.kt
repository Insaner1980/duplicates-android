package com.emma.duplicates.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.Surface
import com.emma.duplicates.data.AudioAlbumArtLoader
import com.emma.duplicates.ui.model.UiFileCategory

@Composable
fun MediaThumbnail(
    category: UiFileCategory,
    thumbnailModel: Any?,
    audioContentUri: String?,
    contentDescription: String?,
    videoImageLoader: ImageLoader,
    audioArtworkVisible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    fallbackIconSize: Dp = size,
) {
    val thumbnailSizePx = with(LocalDensity.current) { size.roundToPx() }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        CategoryIcon(category, Modifier.size(fallbackIconSize))
        when (category) {
            UiFileCategory.PHOTOS -> {
                if (thumbnailModel != null) {
                    AsyncImage(
                        model = thumbnailModel,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            UiFileCategory.VIDEOS -> {
                if (thumbnailModel != null) {
                    AsyncImage(
                        model =
                            buildVideoThumbnailRequest(
                                LocalContext.current,
                                thumbnailModel,
                                thumbnailSizePx,
                            ),
                        imageLoader = videoImageLoader,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Surface.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(R.drawable.ic_play_arrow),
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(16.dp).testTag("video-play-indicator"),
                    )
                }
            }
            UiFileCategory.AUDIO -> {
                AudioAlbumArt(
                    contentUri = audioContentUri,
                    sizePx = thumbnailSizePx,
                    contentDescription = contentDescription,
                    loadArtwork = audioArtworkVisible,
                )
            }
            UiFileCategory.DOCUMENTS -> Unit
        }
    }
}

@Composable
fun rememberVideoThumbnailImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    val imageLoader = remember(context) { buildVideoThumbnailImageLoader(context) }
    DisposableEffect(imageLoader) {
        onDispose { imageLoader.shutdown() }
    }
    return imageLoader
}

internal fun buildVideoThumbnailImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context.applicationContext)
        .components {
            add(VideoFrameDecoder.Factory())
        }.build()

internal fun buildVideoThumbnailRequest(
    context: Context,
    model: Any,
    sizePx: Int,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .size(sizePx, sizePx)
        .decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
        .build()

@Composable
private fun AudioAlbumArt(
    contentUri: String?,
    sizePx: Int,
    contentDescription: String?,
    loadArtwork: Boolean,
) {
    val contentResolver = LocalContext.current.contentResolver
    val loader = remember(contentResolver) { AudioAlbumArtLoader(contentResolver) }
    var bitmap by remember(contentUri, sizePx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(contentUri, sizePx, loadArtwork) {
        if (loadArtwork && contentUri != null && bitmap == null) {
            bitmap = loader.load(contentUri, sizePx)
        }
    }
    bitmap?.let { albumArt ->
        Image(
            bitmap = albumArt.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
