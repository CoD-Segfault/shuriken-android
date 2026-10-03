package lt.gfau.se.shuriken.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lt.gfau.se.shuriken.wigle.WigleCredentialStore
import lt.gfau.se.shuriken.wigle.WigleCredentials
import lt.gfau.se.shuriken.wigle.WigleCsv
import lt.gfau.se.shuriken.wigle.WigleFileException
import lt.gfau.se.shuriken.wigle.WigleGzip
import lt.gfau.se.shuriken.wigle.WigleUpload
import lt.gfau.se.shuriken.wigle.WigleUploadException
import lt.gfau.se.shuriken.wigle.WigleUploadHistory
import lt.gfau.se.shuriken.wigle.UploadedFileKey
import lt.gfau.se.shuriken.wigle.WigleBatch
import lt.gfau.se.shuriken.mtp.MtpFile
import java.io.File

data class WigleState(
    val username: String? = null,
    val fileName: String? = null,
    val busy: Boolean = false,
    val uploading: Boolean = false,
    val uploadedFiles: Set<UploadedFileKey> = emptySet(),
    val showUploaded: Boolean = false,
    val selectedMtpFiles: Set<MtpFile> = emptySet(),
    val blockedUntil: Long = 0,
    val message: String = "Scan the activation QR from wigle.net/activate."
) {
    val canUpload: Boolean get() = !busy && username != null && blockedUntil <= System.currentTimeMillis()
    val cooldownMessage: String get() = if (blockedUntil > System.currentTimeMillis())
        "Uploads paused until ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(blockedUntil))}. No automatic retry."
        else ""
}

class WigleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = WigleCredentialStore(application)
    private val uploader = WigleUpload()
    private val history = WigleUploadHistory(application)
    private var credentials: WigleCredentials? = null
    private var selectedDocuments: List<Pair<Uri, String>> = emptyList()
    private val ratePreferences = application.getSharedPreferences("wigle-rate-limit", android.content.Context.MODE_PRIVATE)
    private var cooldownJob: Job? = null
    private var uploadJob: Job? = null
    private val initialLoad: Job
    private val _state = MutableStateFlow(WigleState(busy = true))
    val state = _state.asStateFlow()

    init {
        initialLoad = viewModelScope.launch {
            try {
                credentials = withContext(Dispatchers.IO) { store.read() }
                val uploaded = withContext(Dispatchers.IO) { history.read() }
                _state.value = WigleState(username = credentials?.username, uploadedFiles = uploaded,
                    blockedUntil = cooldownUntil(credentials?.username),
                    message = if (credentials == null) "Scan the activation QR from wigle.net/activate."
                        else "Credentials saved. Select a CSV log to upload.")
                armCooldown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = WigleState(message = "Stored WiGLE data could not be read. Scan the activation QR again.")
            }
        }
    }

    fun acceptActivation(text: String) {
        if (!initialLoad.isCompleted) {
            viewModelScope.launch { initialLoad.join(); acceptActivation(text) }
            return
        }
        if (_state.value.busy) return
        val parsed = try { WigleCredentials.fromQr(text) } catch (e: IllegalArgumentException) {
            _state.value = _state.value.copy(message = "Invalid activation QR. Expected Username:API_name:API_token.")
            return
        }
        _state.value = _state.value.copy(busy = true, message = "Securing WiGLE credentials…")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(parsed) }
                credentials = parsed
                _state.value = _state.value.copy(username = parsed.username,
                    blockedUntil = cooldownUntil(parsed.username),
                    message = "Credentials saved. Select a CSV log to upload.")
                armCooldown()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not securely save credentials. Please scan again.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun forgetCredentials() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.clear() }
                credentials = null
                cooldownJob?.cancel()
                _state.value = _state.value.copy(username = null, blockedUntil = 0, message = "WiGLE credentials removed.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Could not completely remove credentials. Please try again.")
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun selectFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!initialLoad.isCompleted) {
            viewModelScope.launch { initialLoad.join(); selectFiles(uris) }
            return
        }
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val documents = withContext(Dispatchers.IO) {
                uris.distinct().map { uri ->
                    val name = runCatching {
                        getApplication<Application>().contentResolver.query(uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0)?.take(256) else null
                        }
                    }.getOrNull() ?: "shuriken.csv"
                    uri to name
                }
            }
            selectedDocuments = documents.filterNot { WigleCsv.isDiagnosticFile(it.second) }
            updateDocumentLabel()
            _state.value = _state.value.copy(busy = false,
                message = "${selectedDocuments.size} file(s) selected. " +
                    if (documents.size != selectedDocuments.size) "RESETLOG.CSV was excluded; diagnostics cannot be uploaded."
                    else "Uploads send complete network identifiers and locations to WiGLE.")
        }
    }

    fun upload() {
        if (_state.value.busy) return
        val tasks = selectedDocuments.map { (uri, name) -> UploadTask(name, uri = uri, prepare = { file ->
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw WigleFileException("Could not open the selected CSV. Select it again.")
                    WigleCsv.copyAndValidate(input, file) { ensureActive() }
                }
            }
        }) }
        startBatch(tasks)
    }

    fun uploadMtp(file: MtpFile, download: suspend (File) -> Unit) {
        uploadMtpFiles(listOf(file)) { _, destination -> download(destination) }
    }

    fun uploadMtpFiles(files: List<MtpFile>, download: suspend (MtpFile, File) -> Unit) {
        val tasks = files.distinct().map { file -> UploadTask(file.name, mtp = file, prepare = { snapshot ->
            // The download itself runs on the USB manager's dedicated worker.
            download(file, snapshot)
            withContext(Dispatchers.IO) { WigleCsv.validate(snapshot) }
        }) }
        startBatch(tasks)
    }

    private data class UploadTask(val name: String, val uri: Uri? = null,
        val mtp: MtpFile? = null, val prepare: suspend (File) -> Unit)

    private fun startBatch(tasks: List<UploadTask>) {
        if (_state.value.busy || tasks.isEmpty()) return
        if (tasks.any { WigleCsv.isDiagnosticFile(it.name) }) {
            _state.value = _state.value.copy(message = "RESETLOG.CSV is a reset diagnostic and cannot be uploaded to WiGLE.")
            return
        }
        val auth = credentials ?: run {
            _state.value = _state.value.copy(message = "Scan your activation QR in the WiGLE tab first.")
            return
        }
        if (!_state.value.canUpload) {
            _state.value = _state.value.copy(message = "WiGLE rate-limit cooldown is still active.")
            return
        }
        _state.value = _state.value.copy(busy = true, uploading = true,
            message = "Preparing CSV snapshot…")
        uploadJob = viewModelScope.launch {
            var completed = 0
            var currentName = ""
            try {
                WigleBatch.run(tasks) { task, position ->
                    currentName = task.name
                    var snapshot: File? = null
                    var compressed: File? = null
                    val progress = "$position/${tasks.size}: ${task.name}"
                    try {
                        _state.value = _state.value.copy(message = "$progress — preparing CSV…")
                        // Assign before withContext: cancellation must not lose the temp-file reference.
                        snapshot = File.createTempFile("wigle-upload-", ".csv", getApplication<Application>().cacheDir)
                        val file = snapshot
                        task.prepare(file)
                        val key = UploadedFileKey(task.name, file.length())
                        _state.value = _state.value.copy(message = "$progress — compressing…")
                        compressed = File.createTempFile("wigle-upload-", ".csv.gz", getApplication<Application>().cacheDir)
                        val gzip = compressed
                        withContext(Dispatchers.IO) { WigleGzip.compress(file, gzip) { ensureActive() } }
                        snapshot.delete()
                        _state.value = _state.value.copy(message = "$progress — uploading…")
                        val result = uploader.upload(gzip, auth, task.name)
                        val recorded = withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { history.record(key) }.isSuccess
                        }
                        // Even if disk storage fails, avoid duplicates during this app session.
                        _state.value = _state.value.copy(uploadedFiles = _state.value.uploadedFiles + key,
                            selectedMtpFiles = _state.value.selectedMtpFiles - setOfNotNull(task.mtp))
                        if (task.uri != null) {
                            selectedDocuments = selectedDocuments.filterNot { it.first == task.uri }
                            updateDocumentLabel()
                        }
                        completed++
                        val reference = result.transactionIds.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        _state.value = _state.value.copy(message = "$progress — accepted; processing pending." +
                            if (reference == null) "" else " Transaction $reference.")
                        if (!recorded) throw WigleUploadException("WiGLE accepted this file, but local history could not be saved. Batch stopped; do not re-upload it.")
                    } finally {
                        snapshot?.delete()
                        compressed?.delete()
                    }
                }
                _state.value = _state.value.copy(message = "$completed/${tasks.size} file(s) accepted by WiGLE. Processing is pending.")
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(message =
                    "Batch cancelled: $completed/${tasks.size} accepted. If sending had started, check WiGLE history before retrying. Unsent files remain selected.")
                throw e
            } catch (e: Exception) {
                var cooldownWarning = ""
                if (e is WigleUploadException && e.httpCode == 429) {
                    val until = checkNotNull(e.retryAt)
                    val saved = withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { ratePreferences.edit().putLong(auth.username, until).commit() }.getOrDefault(false)
                    }
                    _state.value = _state.value.copy(blockedUntil = until)
                    armCooldown()
                    if (!saved) cooldownWarning = " Could not persist the cooldown; it applies until this app closes."
                }
                val message = when (e) {
                    is WigleFileException -> e.userMessage
                    is WigleUploadException -> e.userMessage
                    else -> "Could not upload the CSV. Check the file and connection, and WiGLE history before retrying."
                }
                _state.value = _state.value.copy(message = "Batch stopped at $currentName: $completed/${tasks.size} accepted. $message Unsent files remain selected.$cooldownWarning")
            } finally {
                _state.value = _state.value.copy(busy = false, uploading = false)
            }
        }
    }

    fun cancelUpload() { uploadJob?.cancel() }

    private fun updateDocumentLabel() {
        _state.value = _state.value.copy(fileName = selectedDocuments.takeIf { it.isNotEmpty() }
            ?.let { if (it.size == 1) it.single().second else "${it.size} files: " + it.take(8).joinToString { entry -> entry.second } })
    }

    private fun cooldownUntil(username: String?): Long = username?.let { ratePreferences.getLong(it, 0) } ?: 0

    private fun armCooldown() {
        cooldownJob?.cancel()
        val until = _state.value.blockedUntil
        if (until <= System.currentTimeMillis()) return
        cooldownJob = viewModelScope.launch {
            delay((until - System.currentTimeMillis()).coerceAtLeast(0))
            _state.value = _state.value.copy(blockedUntil = 0)
        }
    }

    fun toggleMtpSelection(file: MtpFile) {
        if (_state.value.busy || WigleCsv.isDiagnosticFile(file.name)) return
        val current = _state.value.selectedMtpFiles
        _state.value = _state.value.copy(selectedMtpFiles = if (file in current) current - file else current + file)
    }

    fun selectMtpFiles(files: List<MtpFile>) {
        if (!_state.value.busy) _state.value = _state.value.copy(selectedMtpFiles = files.filterNot { WigleCsv.isDiagnosticFile(it.name) }.toSet())
    }

    fun retainMtpSelection(files: List<MtpFile>) {
        if (_state.value.busy) return
        val retained = _state.value.selectedMtpFiles.intersect(files.toSet())
        if (retained != _state.value.selectedMtpFiles) _state.value = _state.value.copy(selectedMtpFiles = retained)
    }

    fun setShowUploaded(show: Boolean) { _state.value = _state.value.copy(showUploaded = show) }

    override fun onCleared() {
        super.onCleared()
        val active = uploadJob?.takeUnless { it.isCompleted } ?: initialLoad.takeUnless { it.isCompleted }
        if (active == null) history.close() else active.invokeOnCompletion { history.close() }
    }

}
