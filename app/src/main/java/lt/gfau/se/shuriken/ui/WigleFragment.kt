package lt.gfau.se.shuriken.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import lt.gfau.se.shuriken.databinding.FragmentWigleBinding
import lt.gfau.se.shuriken.viewmodel.WigleViewModel
import lt.gfau.se.shuriken.wigle.WigleCsv
import kotlinx.coroutines.launch

class WigleFragment : Fragment() {
    private var _binding: FragmentWigleBinding? = null
    private val viewModel: WigleViewModel by activityViewModels()
    private var confirmation: AlertDialog? = null

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(viewModel::acceptActivation)
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.selectFiles(uris)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?): View {
        return FragmentWigleBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = _binding ?: return
        binding.btnActivateWigle.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wigle.net/activate")))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), "Open https://wigle.net/activate in a browser.", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnScanWigle.setOnClickListener {
            scanner.launch(ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(WigleScanActivity::class.java)
                .setPrompt("Scan the QR at wigle.net/activate")
                .setBarcodeImageEnabled(false)
                .setBeepEnabled(false)
                .setOrientationLocked(false))
        }
        binding.btnForgetWigle.setOnClickListener {
            confirmation = AlertDialog.Builder(requireContext())
                .setTitle("Remove WiGLE credentials?")
                .setMessage("This removes the saved API credentials from this app. It does not revoke them at WiGLE.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ -> viewModel.forgetCredentials() }
                .show()
        }
        binding.btnChooseWigleFile.setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        binding.btnUploadWigle.setOnClickListener {
            val state = viewModel.state.value
            if (!state.canUpload || state.fileName == null) return@setOnClickListener
            confirmation = AlertDialog.Builder(requireContext())
                .setTitle("Upload to WiGLE?")
                .setMessage("Send ${state.fileName} as ${state.username}?\n\n" +
                    "The complete CSV includes network identifiers and locations. " +
                    "Check WiGLE history before re-uploading a previously submitted log.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upload") { _, _ -> viewModel.upload() }
                .show()
        }
        binding.btnCancelWigle.setOnClickListener { viewModel.cancelUpload() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    _binding?.let {
                        it.tvWigleAccount.text = state.username?.let { name -> "Account: $name" }
                            ?: "No WiGLE credentials saved"
                        it.tvWigleFile.text = state.fileName ?: "No CSV selected"
                        it.tvWigleStatus.text = listOf(state.message, state.cooldownMessage).filter { text -> text.isNotEmpty() }.joinToString("\n")
                        it.btnScanWigle.isEnabled = !state.busy
                        it.btnForgetWigle.isEnabled = !state.busy && state.username != null
                        it.btnChooseWigleFile.isEnabled = !state.busy
                        it.btnUploadWigle.isEnabled = state.canUpload &&
                            state.fileName != null && !WigleCsv.isDiagnosticFile(state.fileName)
                        it.btnCancelWigle.visibility = if (state.uploading) View.VISIBLE else View.GONE
                        it.wigleProgress.visibility = if (state.busy) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        confirmation?.dismiss()
        confirmation = null
        _binding = null
        super.onDestroyView()
    }
}
