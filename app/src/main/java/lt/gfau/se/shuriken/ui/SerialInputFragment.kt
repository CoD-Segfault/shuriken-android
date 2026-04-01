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
import lt.gfau.se.shuriken.databinding.FragmentSerialInputBinding
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class SerialInputFragment : Fragment() {

    private var _binding: FragmentSerialInputBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSerialInputBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClearSerial.setOnClickListener { viewModel.clearSerialInputLog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.serialInputLog.collect { chunks ->
                    _binding?.let { b ->
                        val text = if (b.cbHexMode.isChecked) {
                            chunks.joinToString("") { chunk ->
                                chunk.toByteArray(Charsets.ISO_8859_1)
                                    .joinToString(" ") { "%02X".format(it) } + "\n"
                            }
                        } else {
                            chunks.joinToString("")
                        }
                        b.tvSerialLog.text = text
                        if (b.cbAutoScrollSerial.isChecked) {
                            b.scrollSerial.post {
                                b.scrollSerial.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }

        // Rebuild view when hex mode changes
        binding.cbHexMode.setOnCheckedChangeListener { _, _ ->
            _binding?.let { b ->
                val currentLog = viewModel.serialInputLog.value
                val text = if (b.cbHexMode.isChecked) {
                    currentLog.joinToString("") { chunk ->
                        chunk.toByteArray(Charsets.ISO_8859_1)
                            .joinToString(" ") { "%02X".format(it) } + "\n"
                    }
                } else {
                    currentLog.joinToString("")
                }
                b.tvSerialLog.text = text
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
