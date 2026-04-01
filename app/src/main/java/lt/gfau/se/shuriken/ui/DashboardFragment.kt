package lt.gfau.se.shuriken.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import lt.gfau.se.shuriken.R
import lt.gfau.se.shuriken.databinding.FragmentDashboardBinding
import lt.gfau.se.shuriken.model.LocationData
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS 'UTC'", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { 
                    viewModel.locationData.collect { data ->
                        _binding?.let { updateLocation(it, data) }
                    } 
                }
                launch { 
                    viewModel.isTransmitting.collect { tx ->
                        _binding?.let { updateTransmitBadge(it, tx) }
                    } 
                }
            }
        }
    }

    private fun updateLocation(binding: FragmentDashboardBinding, data: LocationData?) {
        if (data == null) {
            binding.fixDot.setBackgroundResource(R.drawable.dot_disconnected)
            binding.tvFixStatus.text = "Awaiting fix…"
            return
        }

        val dotRes = when {
            data.fixQuality == 0 -> R.drawable.dot_disconnected
            data.accuracy <= 10f -> R.drawable.dot_connected
            else -> R.drawable.dot_connecting
        }
        binding.fixDot.setBackgroundResource(dotRes)

        binding.tvFixStatus.text = when (data.fixQuality) {
            0 -> "No fix  (${data.provider})"
            1 -> "GPS fix  ·  ${data.provider}"
            2 -> "DGPS/Network  ·  ${data.provider}"
            else -> "Fix  ·  ${data.provider}"
        }

        binding.tvLatitude.text = "%+.8f°".format(data.latitude)
        binding.tvLongitude.text = "%+.8f°".format(data.longitude)
        binding.tvAltitude.text = "%.1f".format(data.altitude)
        binding.tvSpeed.text = "%.2f".format(data.speed)
        binding.tvBearing.text = "%.1f°".format(data.bearing)
        binding.tvAccuracy.text = "±%.1f".format(data.accuracy)
        binding.tvSatellites.text = data.satellites.toString()
        binding.tvHdop.text = "%.1f".format(data.hdop)
        binding.tvSource.text = data.provider
        binding.tvLastFix.text = timeFmt.format(Date(data.timestamp))
        binding.tvFixQuality.text = when (data.fixQuality) {
            0 -> "0 — Invalid"
            1 -> "1 — GPS"
            2 -> "2 — DGPS"
            else -> data.fixQuality.toString()
        }
    }

    private fun updateTransmitBadge(binding: FragmentDashboardBinding, transmitting: Boolean) {
        binding.tvTransmitStatus.text = if (transmitting) "● TX 5Hz" else "○ idle"
        binding.tvTransmitStatus.setTextColor(
            resources.getColor(
                if (transmitting) R.color.green_fix else R.color.on_surface_variant,
                null
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
