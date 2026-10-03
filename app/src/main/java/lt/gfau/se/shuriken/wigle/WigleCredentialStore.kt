package lt.gfau.se.shuriken.wigle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ciphertext lives in noBackupFilesDir; the AES key never leaves Android Keystore. */
class WigleCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wigle-credentials.v1"))
    private val alias = "shuriken-wigle-api-v1"

    @Synchronized
    fun read(): WigleCredentials? {
        if (!file.baseFile.exists()) return null
        check(file.baseFile.length() in 29..8192) { "Invalid credential storage." }
        val bytes = file.openRead().use { it.readBytes() }
        check(bytes[0] == 1.toByte()) { "Invalid credential storage." }
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: error("Credential key unavailable.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try {
            WigleCredentials.fromQr(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun save(credentials: WigleCredentials) {
        val keyStore = keyStore()
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == 12)
        val plaintext = credentials.storageBytes()
        val encrypted = try { cipher.doFinal(plaintext) } finally { plaintext.fill(0) }
        val stream = file.startWrite()
        try {
            stream.write(byteArrayOf(1))
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        check(!file.baseFile.exists()) { "Could not remove stored credentials." }
        keyStore().deleteEntry(alias)
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
