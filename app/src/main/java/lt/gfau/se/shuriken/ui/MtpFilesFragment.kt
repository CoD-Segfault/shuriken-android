package lt.gfau.se.shuriken.ui

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.flow.combine
import lt.gfau.se.shuriken.R
import lt.gfau.se.shuriken.databinding.FragmentMtpFilesBinding
import lt.gfau.se.shuriken.mtp.MtpFile
import lt.gfau.se.shuriken.viewmodel.MainViewModel
import lt.gfau.se.shuriken.viewmodel.WigleViewModel
import lt.gfau.se.shuriken.wigle.UploadedFileKey
import lt.gfau.se.shuriken.wigle.WigleCsv
import kotlinx.coroutines.launch

class MtpFilesFragment : Fragment() {
    private var _binding: FragmentMtpFilesBinding? = null
    private val viewModel: MainViewModel by activityViewModels()
    private val wigle: WigleViewModel by activityViewModels()
    private var confirmation: AlertDialog? = null
    private var pendingFile: MtpFile? = null
    private val saveDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destination ->
        val file = pendingFile
        pendingFile = null
        if (destination != null && file != null) viewModel.saveMtpFile(file, destination)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getBundle("pendingFile")?.let {
            pendingFile = MtpFile(it.getInt("handle"), it.getString("name").orEmpty(),
                it.getLong("size"), it.getString("session").orEmpty(), it.getLong("listing"))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingFile?.let { file ->
            outState.putBundle("pendingFile", Bundle().apply {
                putInt("handle", file.handle)
                putString("name", file.name)
                putLong("size", file.size)
                putString("session", file.session)
                putLong("listing", file.listing)
            })
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?): View {
        return FragmentMtpFilesBinding.inflate(inflater, container, false).also {
            _binding = it
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = _binding ?: return
        val adapter = FilesAdapter(wigle::toggleMtpSelection) { file ->
            if (pendingFile == null && !viewModel.mtpState.value.busy && !wigle.state.value.busy) {
                confirmation = AlertDialog.Builder(requireContext())
                    .setTitle(file.name)
                    .setItems(if (WigleCsv.isDiagnosticFile(file.name)) arrayOf("Save a copy")
                        else arrayOf("Save a copy", "Upload to WiGLE")) { _, which ->
                        if (which == 0) {
                            pendingFile = file
                            saveDocument.launch(file.name)
                        } else confirmUpload(listOf(file))
                    }.show()
            }
        }
        binding.fileList.layoutManager = LinearLayoutManager(requireContext())
        binding.fileList.adapter = adapter
        binding.btnRefreshFiles.setOnClickListener { viewModel.refreshMtpFiles() }
        binding.cbShowUploaded.setOnCheckedChangeListener { _, checked -> wigle.setShowUploaded(checked) }
        binding.btnCancelFileUpload.setOnClickListener { wigle.cancelUpload() }
        binding.btnSelectAllFiles.setOnClickListener {
            val upload = wigle.state.value
            wigle.selectMtpFiles(viewModel.mtpState.value.files.filter { upload.showUploaded ||
                UploadedFileKey(it.name, it.size) !in upload.uploadedFiles })
        }
        binding.btnClearFileSelection.setOnClickListener { wigle.selectMtpFiles(emptyList()) }
        binding.btnUploadSelectedFiles.setOnClickListener {
            confirmUpload(viewModel.mtpState.value.files.filter { it in wigle.state.value.selectedMtpFiles })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.mtpState, wigle.state) { mtp, upload -> mtp to upload }.collect { (state, upload) ->
                    _binding?.let {
                        val visible = state.files.filter { upload.showUploaded ||
                            UploadedFileKey(it.name, it.size) !in upload.uploadedFiles }
                        val hidden = state.files.size - visible.size
                        it.tvMtpStatus.text = state.message +
                            if (hidden > 0) "\n$hidden uploaded file(s) hidden." else ""
                        it.tvFilesUploadStatus.text = listOf(upload.message, upload.cooldownMessage)
                            .filter { text -> text.isNotEmpty() }.joinToString("\n")
                        it.cbShowUploaded.isChecked = upload.showUploaded
                        it.btnCancelFileUpload.visibility = if (upload.uploading) View.VISIBLE else View.GONE
                        it.btnRefreshFiles.isEnabled = state.connected && !state.busy && !upload.busy
                        it.mtpProgress.visibility = if (state.busy || upload.busy) View.VISIBLE else View.GONE
                        adapter.canSave = state.connected && !state.busy && !upload.busy
                        it.btnSelectAllFiles.isEnabled = adapter.canSave
                        it.btnClearFileSelection.isEnabled = !upload.busy && upload.selectedMtpFiles.isNotEmpty()
                        val count = visible.count { file -> file in upload.selectedMtpFiles }
                        it.btnUploadSelectedFiles.text = "Upload selected ($count)"
                        it.btnUploadSelectedFiles.isEnabled = adapter.canSave && upload.canUpload && count > 0
                        adapter.uploaded = upload.uploadedFiles
                        adapter.selected = upload.selectedMtpFiles
                        adapter.submitList(visible)
                        wigle.retainMtpSelection(visible)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        confirmation?.dismiss()
        confirmation = null
        _binding?.fileList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun confirmUpload(files: List<MtpFile>) {
        if (files.isEmpty()) return
        val state = wigle.state.value
        if (state.busy) return
        if (state.username == null) {
            confirmation = AlertDialog.Builder(requireContext()).setTitle("WiGLE setup needed")
                .setMessage("Scan your activation QR in the WiGLE tab first.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (!state.canUpload) {
            confirmation = AlertDialog.Builder(requireContext()).setTitle("WiGLE rate limit")
                .setMessage(state.cooldownMessage).setPositiveButton("OK", null).show()
            return
        }
        val already = files.any { UploadedFileKey(it.name, it.size) in state.uploadedFiles }
        val names = files.take(8).joinToString("\n") { it.name } + if (files.size > 8) "\n…" else ""
        confirmation = AlertDialog.Builder(requireContext()).setTitle("Upload to WiGLE?")
            .setMessage("Send ${files.size} file(s) as ${state.username}?\n$names\n\n" +
                "The CSV includes network identifiers and locations. " +
                (if (already) "Some selected filenames and sizes were already accepted by WiGLE. Upload again?"
                else "Files will be read and gzip-compressed one at a time. A rate limit or failure stops the batch."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Upload") { _, _ ->
                if (pendingFile == null && !viewModel.mtpState.value.busy) {
                    wigle.uploadMtpFiles(files) { file, destination -> viewModel.downloadMtpFile(file, destination) }
                }
            }.show()
    }

    private class FilesAdapter(private val onSelect: (MtpFile) -> Unit, private val onSave: (MtpFile) -> Unit) :
        ListAdapter<MtpFile, FilesAdapter.Holder>(object : DiffUtil.ItemCallback<MtpFile>() {
            override fun areItemsTheSame(old: MtpFile, new: MtpFile) =
                old.handle == new.handle && old.session == new.session && old.listing == new.listing
            override fun areContentsTheSame(old: MtpFile, new: MtpFile) = old == new
        }) {
        var canSave = false
            set(value) {
                if (field != value) {
                    field = value
                    notifyDataSetChanged()
                }
            }
        var uploaded: Set<UploadedFileKey> = emptySet()
            set(value) {
                if (field != value) { field = value; notifyDataSetChanged() }
            }
        var selected: Set<MtpFile> = emptySet()
            set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

        class Holder(view: View, val selection: CheckBox) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(android.R.id.text1)
            val detail: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val content = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val selection = CheckBox(parent.context)
            val view = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(selection)
                addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            return Holder(view, selection).also {
                it.name.setTextColor(parent.context.getColor(R.color.on_surface))
                it.detail.setTextColor(parent.context.getColor(R.color.on_surface_variant))
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = getItem(position)
            holder.name.text = file.name
            val accepted = UploadedFileKey(file.name, file.size) in uploaded
            holder.detail.text = "${Formatter.formatFileSize(holder.itemView.context, file.size)} · " +
                (if (accepted) "Uploaded · " else "") +
                if (WigleCsv.isDiagnosticFile(file.name)) "Reset diagnostic · Tap to save" else "Tap to save or upload"
            holder.itemView.isEnabled = canSave
            holder.itemView.alpha = if (canSave) 1f else 0.5f
            holder.selection.setOnCheckedChangeListener(null)
            holder.selection.visibility = if (WigleCsv.isDiagnosticFile(file.name)) View.INVISIBLE else View.VISIBLE
            holder.selection.isChecked = file in selected
            holder.selection.isEnabled = canSave && !WigleCsv.isDiagnosticFile(file.name)
            holder.selection.contentDescription = "Select ${file.name} for WiGLE upload"
            holder.selection.setOnCheckedChangeListener { _, _ -> if (canSave) onSelect(file) }
            holder.itemView.setOnClickListener { if (canSave) onSave(file) }
        }
    }
}
