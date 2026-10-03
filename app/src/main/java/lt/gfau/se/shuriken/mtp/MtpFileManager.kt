package lt.gfau.se.shuriken.mtp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.UUID
import java.io.File
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * All native MTP calls, including close, run on one dedicated worker. Never
 * share a connection or a requestWait consumer with either CDC reader.
 */
class MtpFileManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Shuriken-MTP")
    }
    private val _state = MutableStateFlow(MtpState())
    val state = _state.asStateFlow()

    // Guarded by this; the monitor is never held during blocking native I/O.
    private var target: UsbDevice? = null
    private var generation = UUID.randomUUID().toString()
    private var listing = 0L
    private var disposed = false
    // Worker-owned.
    private var mtp: MtpDevice? = null

    @Synchronized
    fun setDevice(device: UsbDevice?) {
        if (disposed || target === device) return
        target = device
        generation = UUID.randomUUID().toString()
        _state.value = MtpState(
            connected = device != null,
            message = if (device == null) "Connect to Shuriken to read SD files."
                else "Tap Refresh to list files on the SD card."
        )
        worker.execute { closeSession() }
    }

    @Synchronized
    fun refresh() {
        val device = target ?: return
        if (disposed || _state.value.busy) return
        val session = generation
        val snapshot = ++listing
        _state.value = MtpState(true, true, message = "Reading SD file list…")
        worker.execute {
            if (!isCurrent(session)) return@execute
            try {
                val reader = openSession(device)
                val storageIds = reader.storageIds ?: error("Could not read MTP storage IDs.")
                val files = mutableListOf<MtpFile>()
                for (storageId in storageIds) {
                    if (!isCurrent(session)) return@execute
                    val handles = reader.getObjectHandles(storageId, 0, -1)
                        ?: error("Could not list SD files. Try Refresh again.")
                    for (handle in handles) {
                        if (!isCurrent(session)) return@execute
                        val info = reader.getObjectInfo(handle)
                            ?: error("Could not read file information. Try Refresh again.")
                        if (info.format != MtpConstants.FORMAT_ASSOCIATION) {
                            files += MtpFile(handle, info.name ?: "file-$handle",
                                info.compressedSizeLong, session, snapshot)
                        }
                    }
                }
                publish(session, MtpState(true, files = files.sortedWith(MTP_FILE_ORDER),
                    message = if (files.isEmpty()) "No readable files in the SD root."
                        else "${files.size} file(s). Tap a file to save or upload."))
            } catch (e: Exception) {
                closeSession()
                publish(session, MtpState(true, message = e.message ?: "MTP listing failed."))
            }
        }
    }

    @Synchronized
    fun save(file: MtpFile, destination: Uri) {
        if (disposed || _state.value.busy) return
        val device = target
        if (device == null || file.session != generation || file.listing != listing ||
            file !in _state.value.files) {
            _state.value = _state.value.copy(message = "File list changed. Refresh and select the file again.")
            return
        }
        val session = generation
        val files = _state.value.files
        _state.value = _state.value.copy(busy = true, message = "Saving ${file.name}…")
        worker.execute {
            if (!isCurrent(session)) return@execute
            try {
                val reader = openSession(device)
                if (!isCurrent(session)) return@execute
                context.contentResolver.openFileDescriptor(destination, "wt").use { descriptor ->
                    checkNotNull(descriptor) { "Could not open the selected destination." }
                    check(reader.importFile(file.handle, descriptor)) {
                        "Download failed; the destination may contain a partial file. Refresh to retry."
                    }
                }
                publish(session, MtpState(true, files = files, message = "Saved ${file.name}."))
            } catch (e: Exception) {
                closeSession()
                publish(session, MtpState(true, files = files,
                    message = e.message ?: "Download failed; the destination may contain a partial file."))
            }
        }
    }

    private fun openSession(device: UsbDevice): MtpDevice {
        mtp?.let { return it }
        check(usbManager.hasPermission(device)) { "USB permission is no longer available." }
        val connection = usbManager.openDevice(device)
            ?: error("Could not open a separate MTP USB connection.")
        val reader = MtpDevice(device)
        try {
            check(reader.open(connection)) { "Could not open MTP. Check firmware support and reconnect." }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        mtp = reader
        return reader
    }

    /** Private-cache download for uploading; native I/O stays on the MTP worker. */
    suspend fun download(file: MtpFile, destination: File): Unit = suspendCancellableCoroutine { continuation ->
        synchronized(this) {
            val device = target
            if (disposed || _state.value.busy || device == null || file.session != generation ||
                file.listing != listing || file !in _state.value.files) {
                continuation.resumeWith(Result.failure(IllegalStateException("File list changed or is busy. Refresh and try again.")))
                return@suspendCancellableCoroutine
            }
            val session = generation
            val files = _state.value.files
            _state.value = _state.value.copy(busy = true, message = "Reading ${file.name} for upload…")
            worker.execute {
                try {
                    check(continuation.isActive && isCurrent(session)) { "Download cancelled or device disconnected." }
                    val reader = openSession(device)
                    val info = checkNotNull(reader.getObjectInfo(file.handle)) { "File no longer available. Refresh." }
                    check(info.name == file.name && info.compressedSizeLong == file.size) {
                        "File changed. Refresh and select it again."
                    }
                    check(file.size <= 100L * 1024 * 1024) { "CSV exceeds the 100 MiB upload limit." }
                    ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { descriptor ->
                        check(continuation.isActive && isCurrent(session)) { "Download cancelled or device disconnected." }
                        check(reader.importFile(file.handle, descriptor)) { "MTP download failed. Refresh to retry." }
                    }
                    check(continuation.isActive && isCurrent(session)) { "Download cancelled or device disconnected." }
                    val after = reader.getObjectInfo(file.handle)
                    check(destination.length() == file.size && after?.name == file.name &&
                        after.compressedSizeLong == file.size) { "File changed during download. Refresh and retry." }
                    publish(session, MtpState(true, files = files, message = "Read ${file.name}."))
                    continuation.resumeWith(Result.success(Unit))
                } catch (e: Exception) {
                    destination.delete()
                    closeSession()
                    publish(session, MtpState(true, files = files, message = e.message ?: "MTP download failed."))
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }
    }

    @Synchronized
    private fun isCurrent(session: String) = !disposed && generation == session

    @Synchronized
    private fun publish(session: String, state: MtpState) {
        if (!disposed && generation == session) _state.value = state
    }

    private fun closeSession() {
        mtp?.let { runCatching { it.close() } }
        mtp = null
    }

    @Synchronized
    fun close() {
        if (disposed) return
        disposed = true
        generation = UUID.randomUUID().toString()
        target = null
        _state.value = MtpState()
        worker.execute { closeSession() }
        worker.shutdown()
    }
}
