package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.database.IndexedFileEntity
import com.emma.duplicates.data.FilePreviewLauncher
import com.emma.duplicates.data.PreviewLaunchResult
import com.emma.duplicates.data.PreviewPreparation
import com.emma.duplicates.data.ScanStore
import com.emma.duplicates.ui.model.UiFileCategory
import com.emma.duplicates.ui.preview.GenericPreviewUiState
import com.emma.duplicates.ui.preview.PhotoPreviewUiState
import com.emma.duplicates.ui.preview.PreviewAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface PreviewScreenState {
    data object Loading : PreviewScreenState

    data class Photo(val state: PhotoPreviewUiState) : PreviewScreenState

    data class Generic(val state: GenericPreviewUiState) : PreviewScreenState
}

class PreviewViewModel(
    private val groupId: String,
    private val fileId: String,
    scanStore: ScanStore,
    private val previewLauncher: FilePreviewLauncher,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PreviewScreenState>(PreviewScreenState.Loading)
    val state: StateFlow<PreviewScreenState> = mutableState
    private var currentFile: IndexedFileEntity? = null

    init {
        viewModelScope.launch {
            scanStore.observeGroup(groupId).collect { group ->
                val file = group?.members?.firstOrNull { it.file.id == fileId }?.file
                currentFile = file
                mutableState.value = file?.toScreenState() ?: missingState()
            }
        }
    }

    fun openExternally() {
        val file = currentFile ?: return
        viewModelScope.launch {
            when (val prepared = previewLauncher.prepare(file)) {
                is PreviewPreparation.Ready -> {
                    if (previewLauncher.launch(prepared) == PreviewLaunchResult.NO_COMPATIBLE_VIEWER) {
                        mutableState.value = file.genericState(PreviewAvailability.NO_COMPATIBLE_VIEWER, false)
                    }
                }
                PreviewPreparation.Missing ->
                    mutableState.value = file.genericState(PreviewAvailability.MISSING, false)
                PreviewPreparation.Failed ->
                    mutableState.value = file.genericState(PreviewAvailability.UNSUPPORTED, false)
            }
        }
    }

    private suspend fun IndexedFileEntity.toScreenState(): PreviewScreenState {
        val category = category.toUiCategory()
        return when (val prepared = previewLauncher.prepare(this)) {
            is PreviewPreparation.Ready ->
                if (category == UiFileCategory.PHOTOS) {
                    PreviewScreenState.Photo(
                        PhotoPreviewUiState(
                            filename = displayName,
                            path = canonicalPath,
                            sizeBytes = sizeBytes,
                            dateEpochMillis = lastModified,
                            imageModel = prepared.uri,
                        ),
                    )
                } else {
                    genericState(PreviewAvailability.UNSUPPORTED, true)
                }
            PreviewPreparation.Missing -> genericState(PreviewAvailability.MISSING, false)
            PreviewPreparation.Failed -> genericState(PreviewAvailability.UNSUPPORTED, false)
        }
    }

    private fun IndexedFileEntity.genericState(
        availability: PreviewAvailability,
        canOpen: Boolean,
    ) =
        PreviewScreenState.Generic(
            GenericPreviewUiState(
                filename = displayName,
                path = canonicalPath,
                sizeBytes = sizeBytes,
                dateEpochMillis = lastModified,
                availability = availability,
                canOpenExternally = canOpen,
            ),
        )

    private fun missingState() =
        PreviewScreenState.Generic(
            GenericPreviewUiState(
                filename = "",
                path = "",
                sizeBytes = 0L,
                dateEpochMillis = 0L,
                availability = PreviewAvailability.MISSING,
                canOpenExternally = false,
            ),
        )
}
