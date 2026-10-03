package lt.gfau.se.shuriken.mtp

import org.junit.Assert.assertEquals
import org.junit.Test

class MtpFileOrderTest {
    @Test fun resetDiagnosticsSortLastWhileOtherFilesStayAlphabetical() {
        val names = listOf("ResetLog.Csv", "z.csv", "reset.csv", "A.csv", "middle.csv")
        val files = names.mapIndexed { index, name -> MtpFile(index, name, 10, "test", 1) }
        assertEquals(listOf("A.csv", "middle.csv", "z.csv", "reset.csv", "ResetLog.Csv"),
            files.sortedWith(MTP_FILE_ORDER).map { it.name })
    }
}
