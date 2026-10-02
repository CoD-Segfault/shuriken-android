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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

class SerialInputFragment : Fragment() {

    private var _binding: FragmentSerialInputBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var autoScrollPending = false
    private val hexMode = MutableStateFlow(false)

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
                launch {
                    viewModel.consoleBytesReceived.collect { bytes ->
                        _binding?.tvSerialInfo?.text = "Console RX: $bytes bytes"
                    }
                }
                combine(viewModel.serialInputLog, hexMode) { chunks, hex -> chunks to hex }
                    .collectLatest { (chunks, hex) ->
                    val text = withContext(Dispatchers.Default) {
                        if (hex) {
                            // Avoid per-byte String.format() on the main thread.
                            val digits = "0123456789ABCDEF"
                            buildString {
                                for (chunk in chunks) {
                                    for (c in chunk) {
                                        val byte = c.code and 0xff
                                        append(digits[byte ushr 4])
                                        append(digits[byte and 0xf])
                                        append(' ')
                                    }
                                    append('\n')
                                }
                            }
                        } else {
                            chunks.joinToString("")
                        }
                    }
                    _binding?.let { b ->
                        b.tvSerialLog.text = text
                        if (b.cbAutoScrollSerial.isChecked && !autoScrollPending) {
                            autoScrollPending = true
                            b.scrollSerial.post {
                                autoScrollPending = false
                                if (_binding === b && b.cbAutoScrollSerial.isChecked) {
                                    b.scrollSerial.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                        }
                    }
                }
            }
        }

        hexMode.value = binding.cbHexMode.isChecked
        binding.cbHexMode.setOnCheckedChangeListener { _, checked -> hexMode.value = checked }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        autoScrollPending = false
        _binding = null
    }
}
