package com.evidencecam.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.evidencecam.app.R
import com.evidencecam.app.databinding.ActivitySettingsBinding
import com.evidencecam.app.model.SegmentDuration
import com.evidencecam.app.model.UploadDestination
import com.evidencecam.app.model.VideoQuality
import com.evidencecam.app.viewmodel.CloudAuthState
import com.evidencecam.app.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        setupUI()
        observeViewModel()

        // Handle OAuth callback if activity was recreated with intent data
        handleOAuthCallback(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupUI() {
        binding.spinnerVideoQuality.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            VideoQuality.entries.map { it.displayName }
        )

        binding.spinnerVideoQuality.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedQuality = VideoQuality.entries[position]
                if (viewModel.settings.value.videoQuality != selectedQuality) {
                    viewModel.updateVideoQuality(selectedQuality)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerSegmentDuration.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            SegmentDuration.entries.map { it.displayName }
        )

        binding.spinnerSegmentDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDuration = SegmentDuration.entries[position]
                if (viewModel.settings.value.segmentDuration != selectedDuration) {
                    viewModel.updateSegmentDuration(selectedDuration)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.sliderMaxStorage.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.updateMaxStoragePercent(value.toInt())
        }

        binding.switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.enableAudio != isChecked) viewModel.updateEnableAudio(isChecked)
        }
        binding.switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.uploadOnWifiOnly != isChecked) viewModel.updateUploadOnWifiOnly(isChecked)
        }
        binding.switchAutoDelete.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.autoDeleteAfterUpload != isChecked) viewModel.updateAutoDeleteAfterUpload(isChecked)
        }
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.keepScreenOn != isChecked) viewModel.updateKeepScreenOn(isChecked)
        }
        binding.switchShowPreview.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.showPreview != isChecked) viewModel.updateShowPreview(isChecked)
        }
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            if (viewModel.settings.value.autoStartRecording != isChecked) viewModel.updateAutoStartRecording(isChecked)
        }

        // Upload destination spinner with friendly names
        binding.spinnerUploadDestination.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            UploadDestination.entries.map { it.displayName }
        )

        binding.spinnerUploadDestination.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedDestination = UploadDestination.entries[position]
                if (viewModel.settings.value.uploadDestination != selectedDestination) {
                    viewModel.updateUploadDestination(selectedDestination)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Dropbox buttons
        binding.btnDropboxConnect.setOnClickListener {
            viewModel.signInToDropbox()
        }

        binding.btnDropboxDisconnect.setOnClickListener {
            viewModel.disconnectDropbox()
        }

        binding.btnCloseApp.setOnClickListener {
            // Stop any active recording, then close the app
            com.evidencecam.app.service.RecordingService.stopRecording(this)
            finishAffinity()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun handleOAuthCallback(intent: Intent) {
        intent.data?.let { uri ->
            if (uri.scheme == "evidencecam" && uri.host == "dropbox") {
                viewModel.handleDropboxAuthCallback(uri)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settings.collectLatest { settings ->
                        // Update spinners
                        val qualityIndex = VideoQuality.entries.indexOf(settings.videoQuality)
                        if (binding.spinnerVideoQuality.selectedItemPosition != qualityIndex) {
                            binding.spinnerVideoQuality.setSelection(qualityIndex)
                        }

                        val durationIndex = SegmentDuration.entries.indexOf(settings.segmentDuration)
                        if (binding.spinnerSegmentDuration.selectedItemPosition != durationIndex) {
                            binding.spinnerSegmentDuration.setSelection(durationIndex)
                        }

                        // Update slider
                        if (binding.sliderMaxStorage.value != settings.maxStoragePercent.toFloat()) {
                            binding.sliderMaxStorage.value = settings.maxStoragePercent.toFloat()
                        }
                        binding.tvMaxStorageValue.text = "${settings.maxStoragePercent}%"


                        // Update switches
                        if (binding.switchAudio.isChecked != settings.enableAudio) {
                            binding.switchAudio.isChecked = settings.enableAudio
                        }
                        if (binding.switchWifiOnly.isChecked != settings.uploadOnWifiOnly) {
                            binding.switchWifiOnly.isChecked = settings.uploadOnWifiOnly
                        }
                        if (binding.switchAutoDelete.isChecked != settings.autoDeleteAfterUpload) {
                            binding.switchAutoDelete.isChecked = settings.autoDeleteAfterUpload
                        }
                        if (binding.switchKeepScreenOn.isChecked != settings.keepScreenOn) {
                            binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
                        }
                        if (binding.switchShowPreview.isChecked != settings.showPreview) {
                            binding.switchShowPreview.isChecked = settings.showPreview
                        }
                        if (binding.switchAutoStart.isChecked != settings.autoStartRecording) {
                            binding.switchAutoStart.isChecked = settings.autoStartRecording
                        }

                        val uploadDestinationIndex = UploadDestination.entries.indexOf(settings.uploadDestination)
                        if (binding.spinnerUploadDestination.selectedItemPosition != uploadDestinationIndex) {
                            binding.spinnerUploadDestination.setSelection(uploadDestinationIndex)
                        }

                        // Show/hide cloud provider config sections
                        binding.layoutDropboxConfig.isVisible = settings.uploadDestination == UploadDestination.DROPBOX
                        binding.layoutCloudOptions.isVisible = settings.uploadDestination != UploadDestination.LOCAL_ONLY
                    }
                }

                // Dropbox config observer
                launch {
                    viewModel.dropboxConfig.collectLatest { config ->
                        val isConnected = config != null
                        binding.tvDropboxStatus.text = if (isConnected) "Connected" else "Not connected"
                        binding.tvDropboxEmail.text = config?.accountEmail ?: ""
                        binding.tvDropboxEmail.isVisible = config?.accountEmail != null
                        binding.ivDropboxStatus.setImageResource(
                            if (isConnected) R.drawable.ic_cloud_done else R.drawable.ic_cloud_off
                        )
                        binding.btnDropboxConnect.isVisible = !isConnected
                        binding.btnDropboxDisconnect.isVisible = isConnected
                        binding.tvDropboxFolderPath.isVisible = isConnected
                        val folderDisplay = if (config?.folderPath.isNullOrEmpty()) "App folder root" else config?.folderPath
                        binding.tvDropboxFolderPath.text = "Uploads to: $folderDisplay"
                    }
                }

                // Dropbox auth state observer
                launch {
                    viewModel.dropboxAuthState.collectLatest { state ->
                        when (state) {
                            is CloudAuthState.Authenticating -> {
                                binding.btnDropboxConnect.isEnabled = false
                                binding.btnDropboxConnect.text = "Connecting..."
                            }
                            is CloudAuthState.Success -> {
                                binding.btnDropboxConnect.isEnabled = true
                                binding.btnDropboxConnect.text = "Connect to Dropbox"
                                viewModel.resetDropboxAuthState()
                            }
                            is CloudAuthState.Error -> {
                                binding.btnDropboxConnect.isEnabled = true
                                binding.btnDropboxConnect.text = "Connect to Dropbox"
                                viewModel.resetDropboxAuthState()
                            }
                            is CloudAuthState.Idle -> {
                                binding.btnDropboxConnect.isEnabled = true
                                binding.btnDropboxConnect.text = "Connect to Dropbox"
                            }
                        }
                    }
                }

                // Dropbox auth intent
                launch {
                    viewModel.dropboxAuthIntent.collectLatest { event ->
                        event.getContentIfNotHandled()?.let { intent ->
                            startActivity(intent)
                        }
                    }
                }

                launch {
                    viewModel.toastMessage.collectLatest { event ->
                        event.getContentIfNotHandled()?.let { Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
}
