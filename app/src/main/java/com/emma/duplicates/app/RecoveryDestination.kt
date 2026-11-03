package com.emma.duplicates.app

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emma.duplicates.domain.deletion.cleanupIdle
import com.emma.duplicates.ui.settings.RecoveryScreen

@Composable
internal fun RecoveryDestination(
    container: AppContainer,
    onBack: () -> Unit,
    model: RecoveryViewModel =
        viewModel(
            factory =
                ViewModelFactory {
                    RecoveryViewModel(container.recoveryRepository, container.deletionOperationMutex) {
                        container.database.cleanupIdle()
                    }
                },
        ),
) {
    val files by model.files.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    RecoveryScreen(files, busy, result, onBack, model::restore, model::dismissResult, Modifier.safeDrawingPadding())
}
