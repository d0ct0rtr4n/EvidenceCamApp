package com.evidencecam.app.ui.main

import android.Manifest
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.evidencecam.app.R
import com.evidencecam.app.databinding.ActivityMainBinding
import com.evidencecam.app.model.RecordingState
import com.evidencecam.app.ui.gallery.GalleryActivity
import com.evidencecam.app.ui.settings.SettingsActivity
import com.evidencecam.app.util.Constants
import com.evidencecam.app.util.FormatUtils
import com.evidencecam.app.util.LocationProvider
import com.evidencecam.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var locationProvider: LocationProvider

    private var overlayUpdateJob: Job? = null
    private var longPressAnimator: ValueAnimator? = null
    private var waitingForRelease = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        }
    }
    
    private var pendingAutoStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filter { !it.value }.keys

        // Check if essential permissions (camera, audio) are granted
        val essentialDenied = deniedPermissions.any {
            it == Manifest.permission.CAMERA || it == Manifest.permission.RECORD_AUDIO
        }

        if (essentialDenied) {
            // Cannot proceed without camera/audio
            pendingAutoStart = false
            showPermissionDeniedDialog(deniedPermissions)
        } else if (deniedPermissions.isNotEmpty()) {
            // Only optional permissions denied (location, notifications) - can still proceed
            showOptionalPermissionWarning(deniedPermissions)
            if (pendingAutoStart) {
                pendingAutoStart = false
                checkPrivacyPolicyAndAutoStart()
            } else {
                checkPrivacyPolicyAndStart()
            }
        } else {
            // All permissions granted
            if (pendingAutoStart) {
                pendingAutoStart = false
                checkPrivacyPolicyAndAutoStart()
            } else {
                checkPrivacyPolicyAndStart()
            }
        }
    }
    
    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_SEGMENT_COMPLETED -> {
                    Toast.makeText(this@MainActivity, "Segment saved", Toast.LENGTH_SHORT).show()
                    viewModel.refreshStorageInfo()
                }
                Constants.ACTION_ERROR -> {
                    Toast.makeText(this@MainActivity, intent.getStringExtra("error_message"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        observeViewModel()
        registerReceivers()

        // Auto-start recording if configured (only on fresh launch, not config changes)
        if (savedInstanceState == null) {
            tryAutoStartRecording()
        }
    }
    
    private fun setupUI() {
        // Push top bar below the system status bar so settings button is accessible
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, insets.top + view.paddingBottom, view.paddingRight, view.paddingBottom)
            windowInsets
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        binding.btnRecord.setOnTouchListener { v, event ->
            val state = viewModel.recordingState.value
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (state is RecordingState.Recording) {
                        startLongPressProgress()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPressProgress()
                    if (waitingForRelease) {
                        // Finger lifted after stop completed - now safe to reset
                        waitingForRelease = false
                    } else if (event.action == MotionEvent.ACTION_UP
                        && (state is RecordingState.Idle || state is RecordingState.Error)
                    ) {
                        v.performClick()
                        checkPermissionsAndStart()
                    }
                    true
                }
                else -> true
            }
        }
        
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.recordingState.collectLatest { updateUIForRecordingState(it) } }
                launch { viewModel.currentDuration.collectLatest { binding.tvDuration.text = FormatUtils.formatDuration(it) } }
                launch {
                    viewModel.storageInfo.collectLatest { info ->
                        info?.let {
                            binding.tvStorageInfo.text = "${FormatUtils.formatFileSize(it.recordingsSize)} (${it.recordingsCount} files)"
                            binding.progressStorage.progress = it.usedPercent.toInt()
                            binding.tvStorageWarning.visibility = if (it.isNearFull) View.VISIBLE else View.GONE
                        }
                    }
                }
                launch {
                    viewModel.settings.collectLatest { settings ->
                        binding.previewView.visibility = if (settings.showPreview) View.VISIBLE else View.INVISIBLE
                        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                launch {
                    viewModel.privacyPolicyAccepted.collectLatest { if (!it) showPrivacyPolicyDialog() }
                }
                launch {
                    viewModel.toastMessage.collectLatest { event ->
                        event.getContentIfNotHandled()?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
    
    private fun updateUIForRecordingState(state: RecordingState) {
        when (state) {
            is RecordingState.Idle -> {
                binding.btnRecord.setImageResource(R.drawable.ic_record)
                binding.btnRecord.isEnabled = true
                binding.recordingIndicator.visibility = View.GONE
                binding.tvStatus.text = "Ready"
                binding.tvRecordHint.text = "Tap to start recording"
                cancelLongPressProgress()
                stopOverlayUpdates()
            }
            is RecordingState.Starting -> {
                binding.btnRecord.isEnabled = false
                binding.tvStatus.text = "Starting..."
                binding.tvRecordHint.text = ""
            }
            is RecordingState.Recording -> {
                binding.btnRecord.setImageResource(R.drawable.ic_stop)
                binding.btnRecord.isEnabled = true
                binding.recordingIndicator.visibility = View.VISIBLE
                binding.tvStatus.text = "Recording (Segment ${state.currentSegment})"
                binding.tvRecordHint.text = "Hold to stop recording"
                viewModel.bindPreviewToService(binding.previewView)
                startOverlayUpdates()
            }
            is RecordingState.Stopping -> {
                binding.btnRecord.isEnabled = false
                binding.tvStatus.text = "Stopping..."
                binding.tvRecordHint.text = ""
                cancelLongPressProgress()
                stopOverlayUpdates()
            }
            is RecordingState.Error -> {
                binding.btnRecord.setImageResource(R.drawable.ic_record)
                binding.btnRecord.isEnabled = true
                binding.recordingIndicator.visibility = View.GONE
                binding.tvStatus.text = "Error: ${state.message}"
                binding.tvRecordHint.text = "Tap to start recording"
                cancelLongPressProgress()
                stopOverlayUpdates()
            }
        }
    }

    private fun startOverlayUpdates() {
        binding.overlayView.visibility = View.VISIBLE
        locationProvider.startLocationUpdates()

        overlayUpdateJob?.cancel()
        overlayUpdateJob = lifecycleScope.launch {
            // Collect location updates
            launch {
                locationProvider.currentLocation.collectLatest { location ->
                    binding.overlayView.updateLocation(location)
                }
            }
            // Update time every second
            while (isActive) {
                binding.overlayView.updateDateTime(System.currentTimeMillis())
                delay(1000)
            }
        }
    }

    private fun stopOverlayUpdates() {
        overlayUpdateJob?.cancel()
        overlayUpdateJob = null
        locationProvider.stopLocationUpdates()
        binding.overlayView.visibility = View.GONE
    }

    private fun startLongPressProgress() {
        binding.longPressProgress.visibility = View.VISIBLE
        binding.longPressProgress.progress = 0

        longPressAnimator?.cancel()
        longPressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = 2000L
            addUpdateListener { animation ->
                binding.longPressProgress.progress = animation.animatedValue as Int
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (binding.longPressProgress.progress >= 100) {
                        waitingForRelease = true
                        viewModel.stopRecording()
                        binding.longPressProgress.visibility = View.INVISIBLE
                        binding.longPressProgress.progress = 0
                    }
                }
            })
            start()
        }
    }

    private fun cancelLongPressProgress() {
        longPressAnimator?.cancel()
        longPressAnimator = null
        binding.longPressProgress.visibility = View.INVISIBLE
        binding.longPressProgress.progress = 0
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) checkPrivacyPolicyAndStart()
        else permissionLauncher.launch(missing.toTypedArray())
    }
    
    private fun checkPrivacyPolicyAndStart() {
        lifecycleScope.launch {
            if (viewModel.privacyPolicyAccepted.value) viewModel.startRecording()
            else showPrivacyPolicyDialog()
        }
    }

    private fun checkPrivacyPolicyAndAutoStart() {
        lifecycleScope.launch {
            if (viewModel.privacyPolicyAccepted.value) {
                viewModel.startRecording()
            }
            // Don't show privacy dialog on auto-start - wait for user to manually try
        }
    }

    private fun tryAutoStartRecording() {
        lifecycleScope.launch {
            val settings = viewModel.settings.first()
            if (settings.autoStartRecording && viewModel.recordingState.value is RecordingState.Idle) {
                val missing = requiredPermissions.filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    checkPrivacyPolicyAndAutoStart()
                } else {
                    pendingAutoStart = true
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }
    }
    
    private fun showPermissionDeniedDialog(deniedPermissions: Set<String>) {
        val deniedNames = deniedPermissions.mapNotNull { permission ->
            when (permission) {
                Manifest.permission.CAMERA -> "Camera"
                Manifest.permission.RECORD_AUDIO -> "Microphone"
                Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                Manifest.permission.READ_MEDIA_VIDEO -> "Media Access"
                else -> null
            }
        }

        val message = buildString {
            append("The following permissions are required to record video:\n\n")
            deniedNames.forEach { append("• $it\n") }
            append("\nPlease grant these permissions in Settings to use the app.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOptionalPermissionWarning(deniedPermissions: Set<String>) {
        val warnings = mutableListOf<String>()

        if (Manifest.permission.ACCESS_FINE_LOCATION in deniedPermissions) {
            warnings.add("GPS coordinates will not be shown on recordings")
        }
        if (Manifest.permission.POST_NOTIFICATIONS in deniedPermissions) {
            warnings.add("You won't receive notifications about recording status")
        }
        if (Manifest.permission.READ_MEDIA_VIDEO in deniedPermissions) {
            warnings.add("Some media features may be limited")
        }

        if (warnings.isNotEmpty()) {
            Toast.makeText(
                this,
                warnings.joinToString(". ") + ".",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun showPrivacyPolicyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy Policy")
            .setMessage("This app records video and audio. You are responsible for complying with local recording laws.")
            .setPositiveButton("I Accept") { _, _ -> viewModel.acceptPrivacyPolicy() }
            .setNegativeButton("View Full Policy") { _, _ ->
                startActivity(Intent(this, PrivacyPolicyActivity::class.java))
            }
            .setCancelable(false)
            .show()
    }
    
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_SEGMENT_COMPLETED)
            addAction(Constants.ACTION_ERROR)
        }
        ContextCompat.registerReceiver(this, recordingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    
    override fun onResume() {
        super.onResume()
        viewModel.refreshStorageInfo()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopOverlayUpdates()
        runCatching { unregisterReceiver(recordingReceiver) }
    }
}
