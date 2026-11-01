package com.emma.duplicates

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.await
import com.emma.duplicates.app.AndroidMediaDeletionAuthorizer
import com.emma.duplicates.app.AppContainer
import com.emma.duplicates.app.AppNavigation
import com.emma.duplicates.app.DuplicatesApplication
import com.emma.duplicates.app.ScanNavigationRequest
import com.emma.duplicates.app.ScanNotificationFactory
import com.emma.duplicates.app.ViewModelFactory
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.data.preferences.AppThemeMode
import com.emma.duplicates.domain.deletion.MediaAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openScanningRequests = MutableSharedFlow<ScanNavigationRequest>(extraBufferCapacity = 1)
    private val deletionRecoveryGate = DeletionRecoveryGate()
    private lateinit var container: AppContainer
    private var scanStartPending by mutableStateOf(false)
    private var notificationPermissionPending = false
    private val deletionAuthorizer: AndroidMediaDeletionAuthorizer by viewModels {
        ViewModelFactory {
            AndroidMediaDeletionAuthorizer(applicationContext.contentResolver)
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (scanStartPending) {
                scanStartPending = false
                notificationPermissionPending = false
                if (!granted) {
                    Toast
                        .makeText(
                            this,
                            R.string.notifications_disabled_scan_continues,
                            Toast.LENGTH_LONG,
                        ).show()
                }
                enqueueScanIfAllowed()
            }
        }
    private val mediaDeletionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val deliveredToLiveRequest =
                deletionAuthorizer.complete(
                    if (result.resultCode == Activity.RESULT_OK) {
                        MediaAuthorization.APPROVED
                    } else {
                        MediaAuthorization.CANCELED
                    },
                )
            if (deletionRecoveryGate.onAuthorizationResult(deliveredToLiveRequest)) {
                reconcilePendingDeletions()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        notificationPermissionPending = savedInstanceState?.getBoolean(STATE_SCAN_START_PENDING) == true
        scanStartPending = notificationPermissionPending
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        container = (application as DuplicatesApplication).container
        val openScanningInitially = intent.consumeScanningDestination()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deletionAuthorizer.launchRequest.collect {
                    deletionAuthorizer.claimLaunchRequest()?.let(mediaDeletionLauncher::launch)
                }
            }
        }

        setContent {
            val mode by container.preferencesRepository.themeMode.collectAsStateWithLifecycle(AppThemeMode.DARK)
            val darkTheme =
                when (mode) {
                    AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                    AppThemeMode.LIGHT -> false
                    AppThemeMode.DARK -> true
                }
            DuplicatesTheme(darkTheme = darkTheme) {
                val background =
                    androidx.compose.material3.MaterialTheme.colorScheme.background
                        .toArgb()
                SideEffect {
                    val barStyle =
                        if (darkTheme) {
                            SystemBarStyle.dark(Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                        }
                    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                    window.setBackgroundDrawable(background.toDrawable())
                }
                AppNavigation(
                    container = container,
                    deletionAuthorizer = deletionAuthorizer,
                    onStartScan = ::requestScanStart,
                    onOpenStorageSettings = {
                        container.storageAccessManager.openSettings(this)
                    },
                    openScanningInitially = openScanningInitially,
                    openScanningRequests = openScanningRequests,
                    scanStartPending = scanStartPending,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.consumeScanningDestination()) {
            openScanningRequests.tryEmit(ScanNavigationRequest())
        }
    }

    override fun onResume() {
        super.onResume()
        if (
            ::container.isInitialized &&
            deletionRecoveryGate.onResume(deletionAuthorizer.hasPendingRequest)
        ) {
            reconcilePendingDeletions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SCAN_START_PENDING, notificationPermissionPending)
        super.onSaveInstanceState(outState)
    }

    private fun requestScanStart() {
        if (scanStartPending) return
        if (!container.storageAccessManager.hasAccess()) {
            container.storageAccessManager.openSettings(this)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            scanStartPending = true
            notificationPermissionPending = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enqueueScanIfAllowed()
        }
    }

    private fun enqueueScanIfAllowed() {
        if (scanStartPending) return
        if (!container.storageAccessManager.hasAccess()) {
            container.storageAccessManager.openSettings(this)
            return
        }
        scanStartPending = true
        lifecycleScope.launch {
            try {
                if (container.scanStore.pendingDeletions().isNotEmpty()) {
                    Toast.makeText(this@MainActivity, R.string.deletion_recovery_pending, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val latest = container.scanStore.latestSession.first()
                if (latest?.status == com.emma.duplicates.core.database.ScanStatuses.RUNNING) {
                    openScanningRequests.emit(ScanNavigationRequest())
                } else {
                    val request = ScanNavigationRequest(latest?.id, awaitNewSession = true)
                    container.scanScheduler.startScan().await()
                    openScanningRequests.emit(request)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, R.string.scan_not_started, Toast.LENGTH_LONG).show()
            } finally {
                scanStartPending = false
            }
        }
    }

    private fun reconcilePendingDeletions() {
        lifecycleScope.launch { container.pendingDeletionReconciler.reconcile() }
    }

    private fun Intent?.consumeScanningDestination(): Boolean {
        val currentIntent = this ?: return false
        val requested =
            currentIntent.getStringExtra(ScanNotificationFactory.EXTRA_DESTINATION) ==
                ScanNotificationFactory.DESTINATION_SCANNING
        if (requested) currentIntent.removeExtra(ScanNotificationFactory.EXTRA_DESTINATION)
        return requested
    }

    private companion object {
        const val STATE_SCAN_START_PENDING = "scan-start-pending"
    }
}

internal class DeletionRecoveryGate {
    private var suppressNextResume = false

    fun onAuthorizationResult(deliveredToLiveRequest: Boolean): Boolean {
        if (deliveredToLiveRequest) suppressNextResume = true
        return !deliveredToLiveRequest
    }

    fun onResume(hasPendingRequest: Boolean): Boolean {
        if (hasPendingRequest) return false
        val shouldReconcile = !suppressNextResume
        if (suppressNextResume) {
            suppressNextResume = false
        }
        return shouldReconcile
    }
}
