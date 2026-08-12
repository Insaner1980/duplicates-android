package com.emma.duplicates.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emma.duplicates.R
import com.emma.duplicates.core.designsystem.DuplicatesCard
import com.emma.duplicates.core.designsystem.Primary
import com.emma.duplicates.core.designsystem.PrimaryActionButton
import com.emma.duplicates.core.format.DisplayFormatters
import com.emma.duplicates.ui.components.ConfirmationDialog
import com.emma.duplicates.ui.components.DetailHeader
import com.emma.duplicates.ui.components.Metric
import java.text.NumberFormat
import java.util.Locale

enum class ScanRunStatus {
    RUNNING,
    COMPLETED,
    CANCELED,
    FAILED,
}

enum class ScanPhase {
    FINDING_FILES,
    COMPARING_CANDIDATES,
    VERIFYING_DUPLICATES,
}

enum class ScanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

data class ScanPhaseUiState(
    val phase: ScanPhase,
    val status: ScanStepStatus,
)

data class ScanningUiState(
    val runStatus: ScanRunStatus,
    val overallProgress: Float?,
    val filesIndexed: Int,
    val groupsFound: Int,
    val groupsAreConfirmed: Boolean,
    val reclaimableBytes: Long,
    val phases: List<ScanPhaseUiState>,
    val currentOperation: String?,
    val currentPath: String?,
    val stopConfirmationVisible: Boolean = false,
    val skippedFileCount: Int = 0,
    val failureMessage: String? = null,
)

@Composable
fun ScanningScreen(
    state: ScanningUiState,
    onBack: () -> Unit,
    onRequestStop: () -> Unit,
    onDismissStop: () -> Unit,
    onConfirmStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding(),
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 24.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            DetailHeader(titleRes = R.string.scanning, onBack = onBack)
            Text(
                text = stringResource(state.runStatus.titleRes()),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )

            ScanOverviewCard(state)

            DuplicatesCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.phases.forEach { phase -> ScanPhaseRow(phase) }
                }
            }

            if (state.currentOperation != null || state.currentPath != null) {
                DuplicatesCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.current_work),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        state.currentOperation?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        state.currentPath?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            state.failureMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.skippedFileCount > 0) {
                Text(
                    text = stringResource(R.string.scan_skipped_files),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DuplicatesCard(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.scan_privacy_note),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.runStatus == ScanRunStatus.RUNNING && !state.stopConfirmationVisible) {
                OutlinedButton(
                    onClick = onRequestStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(stringResource(R.string.stop_scan))
                }
            } else {
                PrimaryActionButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.back))
                }
            }
        }
    }

    if (state.stopConfirmationVisible) {
        ConfirmationDialog(
            title = stringResource(R.string.stop_scan_title),
            message = stringResource(R.string.stop_scan_message),
            confirmLabel = stringResource(R.string.stop_scan),
            onDismiss = onDismissStop,
            onConfirm = onConfirmStop,
            destructive = true,
        )
    }
}

@Composable
private fun ScanPhaseRow(state: ScanPhaseUiState) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("scan-phase-${state.phase.name}"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.status) {
            ScanStepStatus.COMPLETED ->
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(32.dp),
                )
            ScanStepStatus.IN_PROGRESS ->
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
            ScanStepStatus.PENDING ->
                CircularProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.outline,
                )
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(state.phase.labelRes()), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(state.status.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanOverviewCard(state: ScanningUiState) {
    DuplicatesCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScanProgressRing(state.overallProgress, state.runStatus)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Metric(
                    value = englishInteger(state.filesIndexed),
                    label = stringResource(R.string.files_indexed),
                )
                Metric(
                    value = englishInteger(state.groupsFound),
                    label =
                        stringResource(
                            if (state.groupsAreConfirmed) {
                                R.string.duplicate_groups_found
                            } else {
                                R.string.candidate_groups_found
                            },
                        ),
                )
                Metric(
                    value = DisplayFormatters.size(LocalResources.current, state.reclaimableBytes),
                    label = stringResource(R.string.reclaimable_so_far),
                    emphasized = true,
                )
            }
        }
    }
}

@Composable
private fun ScanProgressRing(
    rawProgress: Float?,
    runStatus: ScanRunStatus,
) {
    val progress = rawProgress?.coerceIn(0f, 1f)
    val progressLabel =
        progress?.let { stringResource(R.string.percent_value, (it * 100).toInt()) }
            ?: stringResource(runStatus.titleRes())
    Box(
        modifier =
            Modifier
                .size(190.dp)
                .clearAndSetSemantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo =
                        progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                            ?: ProgressBarRangeInfo.Indeterminate
                },
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = Primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 18.dp,
            )
        } else {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                color = Primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 18.dp,
            )
            Text(
                text = progressLabel,
                style = MaterialTheme.typography.displayLarge,
                color = Primary,
            )
        }
    }
}

private fun ScanRunStatus.titleRes(): Int =
    when (this) {
        ScanRunStatus.RUNNING -> R.string.scanning_for_duplicates
        ScanRunStatus.COMPLETED -> R.string.scan_complete
        ScanRunStatus.CANCELED -> R.string.scan_canceled
        ScanRunStatus.FAILED -> R.string.scan_failed
    }

private fun ScanPhase.labelRes(): Int =
    when (this) {
        ScanPhase.FINDING_FILES -> R.string.finding_files
        ScanPhase.COMPARING_CANDIDATES -> R.string.comparing_candidates
        ScanPhase.VERIFYING_DUPLICATES -> R.string.verifying_duplicates
    }

private fun ScanStepStatus.labelRes(): Int =
    when (this) {
        ScanStepStatus.PENDING -> R.string.pending
        ScanStepStatus.IN_PROGRESS -> R.string.scan_step_in_progress
        ScanStepStatus.COMPLETED -> R.string.completed
    }

private fun englishInteger(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
