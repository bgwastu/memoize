package net.wastu.memoize.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.wastu.memoize.ui.UpdateService

class GitHubUpdateService(private val channel: GitHubAndroidUpdates = GitHubAndroidUpdates()) : UpdateService {
    override suspend fun check(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signer = requireNotNull(info.signingInfo).apkContentsSigners.single()
        val certificate = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        val update = channel.find(info.longVersionCode, certificate) ?: return "Memoize is up to date."
        withContext(Dispatchers.Main) {
            context.startActivity(Intent(Intent.ACTION_VIEW, update.apkAddress.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return "Memoize ${update.versionName} opened in your browser. Install the downloaded APK to update."
    }
}
