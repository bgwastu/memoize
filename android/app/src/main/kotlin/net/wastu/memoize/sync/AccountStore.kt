package net.wastu.memoize.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.accountDataStore by preferencesDataStore(name = "account")

data class AccountConfiguration(val server: String, val user: String, val namespace: String)

class AccountCredentials(val configuration: AccountConfiguration, val token: String) {
    override fun toString(): String = "AccountCredentials(configuration=$configuration, token=<protected>)"
}

/** Nonsecret configuration remains readable when a device-bound credential key is unavailable. */
class AccountStore(context: Context, private val allowLoopbackForTests: Boolean = false) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, "memos-credentials.bin"))

    suspend fun configuration(): AccountConfiguration? {
        val prefs = context.accountDataStore.data.first()
        val server = prefs[SERVER] ?: return null
        val user = prefs[USER] ?: return null
        val namespace = prefs[NAMESPACE] ?: return null
        return AccountConfiguration(server, user, namespace)
    }

    suspend fun load(): AccountCredentials? = lock.withLock {
        val configuration = configuration() ?: return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                val encrypted = file.readFully()
                require(encrypted.size >= 1 + IV_LENGTH + 16 && encrypted[0] == FORMAT)
                val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return@withContext null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(1, 1 + IV_LENGTH)))
                cipher.updateAAD(AAD)
                val plain = cipher.doFinal(encrypted.copyOfRange(1 + IV_LENGTH, encrypted.size))
                val payload = JSONObject(String(plain, StandardCharsets.UTF_8))
                // Crash recovery cannot replay an old or newly swapped token under another origin/account.
                require(payload.getString("server") == configuration.server && payload.getString("user") == configuration.user &&
                    payload.getString("namespace") == configuration.namespace)
                AccountCredentials(configuration, payload.getString("token"))
            }.getOrNull()
        }
    }

    suspend fun save(credentials: AccountCredentials) = lock.withLock {
        val config = credentials.configuration
        require(config.namespace == MemosClient.accountNamespace(MemosClient.normalizedServer(config.server, allowLoopbackForTests), config.user))
        require(credentials.token.isNotBlank() && credentials.token.none { it == '\r' || it == '\n' })
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("server", config.server).put("user", config.user)
                .put("namespace", config.namespace).put("token", credentials.token).toString().toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingOrCreateKey())
            cipher.updateAAD(AAD)
            require(cipher.iv.size == IV_LENGTH)
            val stream = file.startWrite()
            try {
                stream.write(byteArrayOf(FORMAT)); stream.write(cipher.iv); stream.write(cipher.doFinal(payload))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream); throw error
            }
        }
        context.accountDataStore.edit { prefs ->
            prefs[SERVER] = config.server; prefs[USER] = config.user; prefs[NAMESPACE] = config.namespace
        }
    }

    private fun existingOrCreateKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private val lock = Mutex()
        private const val KEY_ALIAS = "net.wastu.memoize.memos-token.v1"
        private const val IV_LENGTH = 12
        private const val FORMAT: Byte = 1
        private val AAD = "Memoize Memos credentials v1".toByteArray(StandardCharsets.UTF_8)
        private val SERVER = stringPreferencesKey("server")
        private val USER = stringPreferencesKey("user")
        private val NAMESPACE = stringPreferencesKey("namespace")
    }
}
