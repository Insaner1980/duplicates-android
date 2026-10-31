package com.emma.duplicates.ui.preview

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.ScreenColumn

enum class PreviewAvailability {
    UNSUPPORTED,
    MISSING,
    NO_COMPATIBLE_VIEWER,
}

data class GenericPreviewUiState(
    val filename: String,
    val path: String,
    val sizeBytes: Long,
    val dateEpochMillis: Long,
    val availability: PreviewAvailability,
    val canOpenExternally: Boolean,
)

data class PhotoPreviewUiState(
    val filename: String,
    val path: String,
    val sizeBytes: Long,
    val dateEpochMillis: Long,
    val imageModel: Any,
)

@Composable
fun PhotoPreviewScreen(
    state: PhotoPreviewUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var imageLoadFailed by remember(state.imageModel) { mutableStateOf(false) }
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DetailHeader(titleRes = R.string.photo_preview, onBack = onBack)
        Text(
            text = state.filename,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (imageLoadFailed) {
            DuplicatesCard(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.preview_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            ZoomablePhoto(state.imageModel, state.filename, onLoadError = { imageLoadFailed = true })
        }
        FileMetadata(
            path = state.path,
            sizeBytes = state.sizeBytes,
            dateEpochMillis = state.dateEpochMillis,
        )
    }
}

@Composable
fun GenericPreviewScreen(
    state: GenericPreviewUiState,
    onBack: () -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        DetailHeader(titleRes = R.string.file_preview, onBack = onBack)
        Text(
            text = state.filename,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        DuplicatesCard(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(state.availability.messageRes()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        FileMetadata(
            path = state.path,
            sizeBytes = state.sizeBytes,
            dateEpochMillis = state.dateEpochMillis,
        )
        if (state.canOpenExternally) {
            PrimaryActionButton(
                onClick = onOpenExternally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_with_another_app))
            }
        }
    }
}

@Composable
private fun FileMetadata(
    path: String,
    sizeBytes: Long,
    dateEpochMillis: Long,
) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.file_size_label,
                        DisplayFormatters.size(LocalResources.current, sizeBytes),
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    stringResource(
                        R.string.file_date_label,
                        DisplayFormatters.dateTime(LocalResources.current, dateEpochMillis),
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.file_path_label, path),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun PreviewAvailability.messageRes(): Int =
    when (this) {
        PreviewAvailability.UNSUPPORTED -> R.string.preview_unavailable
        PreviewAvailability.MISSING -> R.string.file_missing
        PreviewAvailability.NO_COMPATIBLE_VIEWER -> R.string.no_compatible_viewer
    }

private const val MAXIMUM_PHOTO_SCALE = 5f

@Composable
private fun ZoomablePhoto(
    imageModel: Any,
    filename: String,
    onLoadError: () -> Unit,
) {
    var scale by remember(imageModel) { mutableFloatStateOf(1f) }
    var offset by remember(imageModel) { mutableStateOf(Offset.Zero) }
    val zoomDescription = stringResource(R.string.zoom_photo_description, filename)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .pointerInput(imageModel) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, MAXIMUM_PHOTO_SCALE)
                        scale = nextScale
                        offset = if (nextScale == 1f) Offset.Zero else offset + pan
                    }
                }.pointerInput(imageModel) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        },
                    )
                }.semantics {
                    contentDescription = zoomDescription
                },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onError = { onLoadError() },
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        )
    }
}
