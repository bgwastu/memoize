package net.wastu.memoize.updates

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubAndroidUpdatesTest {
    @Test fun findsAndroidBeyondFullMacPageAndRejectsDraftsAndWrongSigner() = runTest {
        val api = object : PublicReleaseApi {
            override suspend fun get(address: String): ReleaseDocument = when {
                address == GitHubAndroidUpdates.start -> ReleaseDocument(
                    "[" + (1..100).joinToString(",") { """{"tag_name":"v0.1.$it","draft":false,"prerelease":false,"assets":[]}""" } + "]",
                    "${GitHubAndroidUpdates.start}&page=2",
                )
                address.endsWith("page=2") -> ReleaseDocument("""[
                    {"tag_name":"android/v0.1.99","draft":true,"prerelease":false,"assets":[]},
                    {"tag_name":"android/v0.1.5","draft":false,"prerelease":false,"assets":[
                      {"name":"release.json","browser_download_url":"https://github.com/bgwastu/memoize/releases/download/android%2Fv0.1.5/release.json"},
                      {"name":"Memoize-0.1.5.apk","browser_download_url":"https://github.com/bgwastu/memoize/releases/download/android%2Fv0.1.5/Memoize-0.1.5.apk"}]}
                ]""")
                address.endsWith("release.json") -> ReleaseDocument("""{
                    "schema":1,"repository":"bgwastu/memoize","platform":"android","source_sha":"${"a".repeat(40)}",
                    "version_name":"0.1.5","version_code":5,"tag":"android/v0.1.5",
                    "signing_certificate_sha256":"${"b".repeat(64)}","assets":{"Memoize-0.1.5.apk":"${"c".repeat(64)}"}
                }""")
                else -> error("Unexpected public request: $address")
            }
        }
        val channel = GitHubAndroidUpdates(api)
        assertEquals(5, channel.find(4, "b".repeat(64))?.versionCode)
        assertEquals(null, channel.find(5, "b".repeat(64)))
        assertEquals(null, channel.find(4, "d".repeat(64)))
    }
}
