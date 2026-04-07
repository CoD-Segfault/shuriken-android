package lt.gfau.se.shuriken

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import lt.gfau.se.shuriken.databinding.ActivityMainBinding
import lt.gfau.se.shuriken.serial.UsbSerialManager
import lt.gfau.se.shuriken.ui.DeviceSelectDialogFragment
import lt.gfau.se.shuriken.ui.MainPagerAdapter
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineLocationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        
        if (fineLocationGranted) {
            viewModel.startLocation()
        } else {
            Toast.makeText(this, "Precise location is required for NMEA accuracy.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupViewPager()
        setupStatusBar()
        checkAndRequestPermissions()

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            lifecycleScope.launch {
                // Wait for service connection and initial enumeration
                var retry = 0
                while (viewModel.availablePorts.value.isEmpty() && retry < 10) {
                    viewModel.refreshDevices()
                    delay(300)
                    retry++
                }
                
                val ports = viewModel.availablePorts.value
                if (ports.size == 1) {
                    viewModel.connectToPort(ports[0])
                } else if (ports.size > 1) {
                    showDeviceSelectDialog()
                }
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = when (pos) { 0 -> "Dashboard"; 1 -> "NMEA Out"; else -> "Serial Console" }
        }.attach()
    }

    private fun setupStatusBar() {
        binding.btnRefreshDevices.setOnClickListener {
            viewModel.refreshDevices()
            val n = viewModel.availablePorts.value.size
            Toast.makeText(this, "Found $n device(s)", Toast.LENGTH_SHORT).show()
            if (n > 0) showDeviceSelectDialog()
        }

        binding.btnConnectDisconnect.setOnClickListener {
            when (viewModel.connectionState.value) {
                UsbSerialManager.ConnectionState.CONNECTED -> viewModel.disconnect()
                else -> {
                    viewModel.refreshDevices()
                    val ports = viewModel.availablePorts.value
                    when {
                        ports.isEmpty() -> Toast.makeText(
                            this, getString(R.string.no_devices_found), Toast.LENGTH_SHORT
                        ).show()
                        ports.size == 1 -> viewModel.connectToPort(ports[0])
                        else -> showDeviceSelectDialog()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        val (dotRes, label, btnLabel) = when (state) {
                            UsbSerialManager.ConnectionState.DISCONNECTED ->
                                Triple(R.drawable.dot_disconnected, "Disconnected", "Connect")
                            UsbSerialManager.ConnectionState.CONNECTING ->
                                Triple(R.drawable.dot_connecting, "Connecting…", "Cancel")
                            UsbSerialManager.ConnectionState.CONNECTED ->
                                Triple(R.drawable.dot_connected, "Connected", "Disconnect")
                            UsbSerialManager.ConnectionState.ERROR ->
                                Triple(R.drawable.dot_disconnected, "Error — tap to retry", "Retry")
                        }
                        binding.connectionDot.setBackgroundResource(dotRes)
                        binding.tvConnectionStatus.text = label
                        binding.btnConnectDisconnect.text = btnLabel
                    }
                }
                launch {
                    viewModel.connectedPortLabel.collect { label ->
                        if (label.isNotEmpty()) {
                            binding.tvConnectionStatus.text = "Connected: $label"
                        }
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            viewModel.startLocation()
            // Still check if we need to request notification permission on API 33+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasNotifications = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!hasNotifications) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun showDeviceSelectDialog() {
        if (supportFragmentManager.findFragmentByTag(DeviceSelectDialogFragment.TAG) == null) {
            DeviceSelectDialogFragment().show(supportFragmentManager, DeviceSelectDialogFragment.TAG)
        }
    }
}
