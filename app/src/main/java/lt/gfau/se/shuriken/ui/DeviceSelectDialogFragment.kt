package lt.gfau.se.shuriken.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import lt.gfau.se.shuriken.adapter.SerialPortAdapter
import lt.gfau.se.shuriken.databinding.DialogDeviceSelectBinding
import lt.gfau.se.shuriken.model.SerialDevicePort
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DeviceSelectDialogFragment : DialogFragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: DialogDeviceSelectBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogDeviceSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDeviceSelectBinding.inflate(layoutInflater)

        val ports = viewModel.availablePorts.value

        binding.rvPorts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPorts.adapter = SerialPortAdapter(ports) { port ->
            onPortSelected(port)
        }

        binding.btnCancel.setOnClickListener { dismiss() }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .create()
            .also { it.window?.setBackgroundDrawableResource(android.R.color.transparent) }
    }

    private fun onPortSelected(port: SerialDevicePort) {
        viewModel.connectToPort(port)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DeviceSelectDialog"
    }
}
