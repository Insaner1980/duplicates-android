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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.emma.duplicates.app.AndroidMediaDeletionAuthorizer
import com.emma.duplicates.app.AppContainer
import com.emma.duplicates.app.AppNavigation
import com.emma.duplicates.app.DuplicatesApplication
import com.emma.duplicates.app.ScanNotificationFactory
import com.emma.duplicates.app.ViewModelFactory
import com.emma.duplicates.core.designsystem.DuplicatesTheme
import com.emma.duplicates.domain.deletion.MediaAuthorization
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openScanningRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val deletionRecoveryGate = DeletionRecoveryGate()
    private lateinit var container: AppContainer
    private var scanStartPending = false
    private val deletionAuthorizer: AndroidMediaDeletionAuthorizer by viewModels {
        ViewModelFactory {
            AndroidMediaDeletionAuthorizer(applicationContext.contentResolver)
        }
    }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (scanStartPending) {
                scanStartPending = false
                if (!granted) {
                    Toast.makeText(
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
            val deliveredToLiveRequest = deletionAuthorizer.complete(
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
        scanStartPending = savedInstanceState?.getBoolean(STATE_SCAN_START_PENDING) == true
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
            DuplicatesTheme {
                AppNavigation(
                    container = container,
                    deletionAuthorizer = deletionAuthorizer,
                    onStartScan = ::requestScanStart,
                    onOpenStorageSettings = {
                        container.storageAccessManager.openSettings(this)
                    },
                    openScanningInitially = openScanningInitially,
                    openScanningRequests = openScanningRequests,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.consumeScanningDestination()) {
            openScanningRequests.tryEmit(Unit)
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
        outState.putBoolean(STATE_SCAN_START_PENDING, scanStartPending)
        super.onSaveInstanceState(outState)
    }

    private fun requestScanStart() {
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
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enqueueScanIfAllowed()
        }
    }

    private fun enqueueScanIfAllowed() {
        if (!container.storageAccessManager.hasAccess()) {
            container.storageAccessManager.openSettings(this)
            return
        }
        container.scanScheduler.startScan()
        openScanningRequests.tryEmit(Unit)
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
        if (suppressNextResume) {
            suppressNextResume = false
            return false
        }
        return true
    }
}
