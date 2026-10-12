package net.wastu.memoize.updates

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ReleaseDocument(val body: String, val next: String? = null)

/** Public GitHub transport; it never receives an account token or server URL. */
interface PublicReleaseApi {
    suspend fun get(address: String): ReleaseDocument
}

data class AndroidUpdate(val versionName: String, val versionCode: Int, val apkAddress: String)

class GitHubAndroidUpdates(private val api: PublicReleaseApi = UrlConnectionReleaseApi()) {
    suspend fun find(installedCode: Long, certificate: String): AndroidUpdate? {
        val releases = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val visited = mutableSetOf<String>()
        var page: String? = start
        while (page != null) {
            val address = page
            require(validPage(address) && visited.add(address)) { "Invalid or repeated GitHub release page" }
            val document = api.get(address)
            releases += Json.parseToJsonElement(document.body).jsonArray.map { it.jsonObject }
            page = document.next
        }
        var newest: AndroidUpdate? = null
        for (release in releases) {
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!tag.matches(Regex("android/v[0-9]+\\.[0-9]+\\.[0-9]+")) ||
                release["draft"]?.jsonPrimitive?.booleanOrNull != false ||
                release["prerelease"]?.jsonPrimitive?.booleanOrNull != false) continue
            val name = tag.removePrefix("android/v")
            val assets = release["assets"]?.jsonArray?.associate { asset ->
                val fields = asset.jsonObject
                fields["name"]!!.jsonPrimitive.content to fields["browser_download_url"]!!.jsonPrimitive.content
            } ?: continue
            val filename = "Memoize-$name.apk"
            val apk = assets[filename] ?: continue
            val manifest = assets["release.json"] ?: continue
            require(validAsset(apk, tag, filename) && validAsset(manifest, tag, "release.json")) { "Invalid Android release asset origin" }
            val metadata = Json.parseToJsonElement(api.get(manifest).body).jsonObject
            val code = metadata["version_code"]?.jsonPrimitive?.intOrNull ?: continue
            if (metadata["schema"]?.jsonPrimitive?.intOrNull != 1 ||
                metadata["repository"]?.jsonPrimitive?.contentOrNull != "bgwastu/memoize" ||
                metadata["platform"]?.jsonPrimitive?.contentOrNull != "android" ||
                metadata["tag"]?.jsonPrimitive?.contentOrNull != tag ||
                metadata["version_name"]?.jsonPrimitive?.contentOrNull != name ||
                metadata["source_sha"]?.jsonPrimitive?.contentOrNull?.matches(Regex("[0-9a-f]{40}")) != true ||
                metadata["assets"]?.jsonObject?.get(filename)?.jsonPrimitive?.contentOrNull?.matches(Regex("[0-9a-f]{64}")) != true ||
                metadata["signing_certificate_sha256"]?.jsonPrimitive?.contentOrNull?.lowercase() != certificate.lowercase() ||
                code !in 1..2_100_000_000 || code <= installedCode || code <= (newest?.versionCode ?: 0)) continue
            newest = AndroidUpdate(name, code, apk)
        }
        return newest
    }

    private fun validPage(address: String): Boolean = URI(address).let {
        it.scheme == "https" && it.host == "api.github.com" && it.userInfo == null && it.port == -1 &&
            it.path == "/repos/bgwastu/memoize/releases" && it.fragment == null
    }

    private fun validAsset(address: String, tag: String, filename: String): Boolean = URI(address).let {
        it.scheme == "https" && it.host == "github.com" && it.userInfo == null && it.port == -1 &&
            it.path == "/bgwastu/memoize/releases/download/$tag/$filename" && it.query == null && it.fragment == null
    }

    companion object {
        const val start = "https://api.github.com/repos/bgwastu/memoize/releases?per_page=100"
    }
}

class UrlConnectionReleaseApi : PublicReleaseApi {
    override suspend fun get(address: String): ReleaseDocument = withContext(Dispatchers.IO) {
        var current = URI(address)
        repeat(6) {
            require(current.scheme == "https" && current.userInfo == null && current.port == -1 &&
                current.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "Invalid public release origin"
            }
            val connection = current.toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Memoize-Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode in 300..399) {
                    current = current.resolve(connection.getHeaderField("Location") ?: error("Release redirect has no destination"))
                } else {
                    check(connection.responseCode == 200) { "GitHub update request failed (${connection.responseCode})" }
                    val bytes = connection.inputStream.use { input ->
                        input.readNBytes(8 * 1024 * 1024 + 1).also { check(it.size <= 8 * 1024 * 1024) { "Release response is too large" } }
                    }
                    val next = connection.getHeaderField("Link")?.split(',')?.firstNotNullOfOrNull { link ->
                        Regex("<([^>]+)>;\\s*rel=\"next\"").find(link)?.groupValues?.get(1)
                    }
                    return@withContext ReleaseDocument(bytes.toString(Charsets.UTF_8), next)
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many public release redirects")
    }
}
