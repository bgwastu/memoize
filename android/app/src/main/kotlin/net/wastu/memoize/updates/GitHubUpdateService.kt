package net.wastu.memoize.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.wastu.memoize.ui.UpdateService
import net.wastu.memoize.ui.UpdateStatus
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class GitHubUpdateService(private val channel: GitHubAndroidUpdates = GitHubAndroidUpdates()) : UpdateService {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    override val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    override suspend fun check(context: Context): String {
        _status.value = UpdateStatus.Checking
        try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
            val signer = requireNotNull(info.signingInfo).apkContentsSigners.single()
            val certificate = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
            val update = channel.find(info.longVersionCode, certificate)
            if (update == null) {
                _status.value = UpdateStatus.UpToDate()
                return "Memoize is up to date."
            }
            _status.value = UpdateStatus.Available(update)
            return "Memoize ${update.versionName} is available."
        } catch (e: Exception) {
            val msg = e.message ?: "Unable to check for updates"
            _status.value = UpdateStatus.Error(msg)
            throw e
        }
    }

    override suspend fun downloadAndInstall(context: Context) {
        val current = _status.value
        val update = when (current) {
            is UpdateStatus.Available -> current.update
            is UpdateStatus.ReadyToInstall -> {
                install(context)
                return
            }
            else -> return
        }

        _status.value = UpdateStatus.Downloading(update, 0f)
        try {
            val apkFile = downloadApk(context, update) { progress ->
                _status.value = UpdateStatus.Downloading(update, progress)
            }
            _status.value = UpdateStatus.ReadyToInstall(update, apkFile)
            withContext(Dispatchers.Main) {
                install(context)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to download update"
            _status.value = UpdateStatus.Error(msg)
            throw e
        }
    }

    override fun install(context: Context) {
        val ready = _status.value as? UpdateStatus.ReadyToInstall ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", ready.file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun downloadApk(
        context: Context,
        update: AndroidUpdate,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destination = File(updatesDir, "Memoize-${update.versionName}.apk")
        val temporary = File.createTempFile(".memoize-update-", ".apk.part", updatesDir)

        try {
            var currentUrl = update.apkAddress
            var targetConnection: HttpURLConnection? = null
            for (hop in 0..6) {
                val url = URI(currentUrl).toURL()
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Memoize-Android")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    currentUrl = connection.getHeaderField("Location") ?: error("Redirect missing Location")
                    connection.disconnect()
                } else if (code == 200) {
                    targetConnection = connection
                    break
                } else {
                    error("Download failed with HTTP $code")
                }
            }

            val target = targetConnection ?: error("Unable to connect to download server")
            val totalBytes = target.contentLengthLong
            var readBytes = 0L
            val digest = if (update.sha256.isNotBlank()) MessageDigest.getInstance("SHA-256") else null

            target.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)
                        readBytes += count
                        if (totalBytes > 0) {
                            onProgress((readBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    output.fd.sync()
                }
            }

            if (update.sha256.isNotBlank() && digest != null) {
                val computed = digest.digest().joinToString("") { "%02x".format(it) }
                check(computed.equals(update.sha256, ignoreCase = true)) {
                    "Update download verification failed (checksum mismatch)"
                }
            }

            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Failed to move update file" }
            destination
        } finally {
            temporary.delete()
        }
    }
}
