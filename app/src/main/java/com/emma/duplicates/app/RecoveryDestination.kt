package com.emma.duplicates.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emma.duplicates.ui.settings.RecoveryScreen

@Composable
internal fun RecoveryDestination(
    container: AppContainer,
    onBack: () -> Unit,
    model: RecoveryViewModel =
        viewModel(factory = ViewModelFactory { RecoveryViewModel(container.recoveryRepository) }),
) {
    val files by model.files.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    RecoveryScreen(files, busy, result, onBack, model::restore, model::dismissResult)
}
