package com.emma.duplicates.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emma.duplicates.core.database.ToolItemEntity
import com.emma.duplicates.core.model.CleanupTool
import com.emma.duplicates.core.model.ToolScope
import com.emma.duplicates.core.storage.allows
import com.emma.duplicates.data.FilePreviewLauncher
import com.emma.duplicates.data.PreviewLaunchResult
import com.emma.duplicates.data.PreviewPreparation
import com.emma.duplicates.data.ToolResultStore
import com.emma.duplicates.ui.preview.GenericPreviewUiState
import com.emma.duplicates.ui.preview.PhotoPreviewUiState
import com.emma.duplicates.ui.preview.PreviewAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

class ToolPreviewViewModel(
    private val sessionId: String,
    private val itemId: String,
    private val store: ToolResultStore,
    private val launcher: FilePreviewLauncher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow<PreviewScreenState>(PreviewScreenState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableCanKeep = MutableStateFlow(false)
    val canKeep = mutableCanKeep.asStateFlow()
    private var item: ToolItemEntity? = null

    init {
        viewModelScope.launch {
            store.dao.observeResults(sessionId).collect { results ->
                val current = results?.items?.firstOrNull { it.id == itemId && it.valid }
                item = current
                mutableCanKeep.value =
                    results?.session?.active == true && results.session.tool == CleanupTool.SIMILAR_PHOTOS.name &&
                    current != null &&
                    !current.keeper
                val available =
                    withContext(ioDispatcher) {
                        current != null &&
                            ToolScope.decode(results.session.scope).allows(current.canonicalPath, current.volume) &&
                            matchesPreviewSnapshot(current)
                    }
                val prepared = if (available) launcher.prepare(requireNotNull(current)) else PreviewPreparation.Missing
                mutableState.value =
                    if (prepared is PreviewPreparation.Ready && current?.category == "PHOTOS") {
                        PreviewScreenState.Photo(
                            PhotoPreviewUiState(
                                current.name,
                                current.canonicalPath,
                                current.sizeBytes,
                                current.modifiedAt,
                                prepared.uri,
                            ),
                        )
                    } else {
                        generic(
                            current,
                            if (prepared ==
                                PreviewPreparation.Missing
                            ) {
                                PreviewAvailability.MISSING
                            } else {
                                PreviewAvailability.UNSUPPORTED
                            },
                            prepared is PreviewPreparation.Ready && current != null && canOpen(current),
                        )
                    }
            }
        }
    }

    fun keep() = viewModelScope.launch { if (canKeep.value) store.keep(sessionId, itemId) }

    fun open() {
        val current = item?.takeIf(::canOpen) ?: return
        viewModelScope.launch {
            val ready = launcher.prepare(current)
            if (ready !is PreviewPreparation.Ready || launcher.launch(ready) != PreviewLaunchResult.OPENED) {
                mutableState.value = generic(current, PreviewAvailability.NO_COMPATIBLE_VIEWER, false)
            }
        }
    }

    private fun canOpen(item: ToolItemEntity) =
        !item.name.endsWith(".apk", ignoreCase = true) && item.mimeType != "application/vnd.android.package-archive"

    private fun matchesPreviewSnapshot(item: ToolItemEntity): Boolean =
        try {
            val path = File(item.canonicalPath).toPath()
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            attributes.isRegularFile && Files.isReadable(path) && attributes.size() == item.sizeBytes &&
                attributes.lastModifiedTime().toMillis() == item.modifiedAt
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun generic(
        item: ToolItemEntity?,
        availability: PreviewAvailability,
        canOpen: Boolean,
    ) = PreviewScreenState.Generic(
        GenericPreviewUiState(
            item?.name.orEmpty(),
            item?.canonicalPath.orEmpty(),
            item?.sizeBytes ?: 0,
            item?.modifiedAt ?: 0,
            availability,
            canOpen,
        ),
    )
}
