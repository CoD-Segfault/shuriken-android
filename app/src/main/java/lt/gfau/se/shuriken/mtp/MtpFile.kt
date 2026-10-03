package lt.gfau.se.shuriken.mtp

/** Reset diagnostics follow all other files, regardless of filename case. */
internal val MTP_FILE_ORDER = compareBy<MtpFile> {
    it.name.equals("RESETLOG.CSV", ignoreCase = true) || it.name.equals("RESET.CSV", ignoreCase = true)
}.thenBy { it.name.lowercase(java.util.Locale.ROOT) }

data class MtpFile(
    val handle: Int,
    val name: String,
    val size: Long,
    val session: String,
    val listing: Long
)

data class MtpState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val files: List<MtpFile> = emptyList(),
    val message: String = "Connect to Shuriken to read SD files."
)
