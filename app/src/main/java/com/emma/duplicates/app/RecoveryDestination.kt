package com.emma.duplicates.app

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emma.duplicates.domain.deletion.cleanupIdle
import com.emma.duplicates.ui.settings.RecoveryScreen

@Composable
internal fun RecoveryDestination(
    container: AppContainer,
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
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.onResume()
        onPauseOrDispose { }
    }
    RecoveryScreen(
        state,
        model::select,
        model::restoreSelected,
        model::requestDelete,
        model::confirmDelete,
        model::dismissDelete,
        model::dismissResult,
        Modifier.safeDrawingPadding(),
    )
}
