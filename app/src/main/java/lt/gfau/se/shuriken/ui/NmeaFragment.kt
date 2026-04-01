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
import lt.gfau.se.shuriken.databinding.FragmentNmeaBinding
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class NmeaFragment : Fragment() {

    private var _binding: FragmentNmeaBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNmeaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClearNmea.setOnClickListener { viewModel.clearNmeaLog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nmeaLog.collect { lines ->
                    _binding?.let { b ->
                        b.tvNmeaLog.text = lines.joinToString("\n")
                        if (b.cbAutoScroll.isChecked) {
                            b.scrollNmea.post {
                                b.scrollNmea.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isTransmitting.collect { tx ->
                    _binding?.let { b ->
                        b.tvNmeaRate.text = if (tx) "NMEA Output  ●  5 Hz" else "NMEA Output  ○  stopped"
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
