package net.wastu.memoize.sync

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.wastu.memoize.R
import net.wastu.memoize.data.EditSnapshot
import net.wastu.memoize.data.MemoSnapshot
import net.wastu.memoize.data.NoteAttachment
import net.wastu.memoize.data.NoteRepository
import net.wastu.memoize.ui.AppGraph
import net.wastu.memoize.widget.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.UUID

/** A fresh storage root receives only files permitted by the shipped extraction policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class KeylessRestoreTest {
    @Test
    fun restoringWithoutCredentialsKeepsPendingNotesVersionsOriginalsAndWidgetConfiguration() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "keyless-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val source = StorageContext(base, File(root, "source"))
        val restored = StorageContext(base, File(root, "restored"))
        val server = "https://notes.example.test"
        val user = "users/42"
        val namespace = MemosClient.accountNamespace(MemosClient.normalizedServer(server), user)
        val local = NoteRepository.open(source)
        val configured = local.forAccount(namespace)
        val original = File(configured.originalsDirectory, "original.txt").apply { writeText("Exact restored original 📝") }
        try {
            local.create("offline-draft")
            local.saveEdit("offline-draft", EditSnapshot("Never published offline source"))
            val file = NoteAttachment(id = "original-file", remoteName = "attachments/original-file", filename = "original.txt",
                mimeType = "text/plain", size = original.length(), uploaded = true)
            configured.applyRemote(MemoSnapshot("memos/pending", "Acknowledged base", 1, 1, attachments = listOf(file)))
            configured.recordAttachmentOriginal(file.id, original.absolutePath)
            configured.saveEdit("pending", EditSnapshot("In-flight source **literal**"))
            configured.beginSend("pending")
            configured.saveEdit("pending", EditSnapshot("Newer pending source 📝", 4, 9, 17))
            val removed = File(configured.originalsDirectory, "removed.txt").apply { writeText("Removed recovery original") }
            configured.addAttachment("pending", NoteAttachment(id = "removed-file", filename = "removed.txt",
                mimeType = "text/plain", size = removed.length(), localPath = removed.absolutePath))
            configured.removeAttachment("pending", "removed-file")
        } finally { local.close() }

        // Historic, nonsecret on-disk configuration is the restore input. Credential ciphertext
        // and device-bound keys are deliberately absent from the fresh installation.
        val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val accounts = PreferenceDataStoreFactory.create(scope = preferencesScope) {
                File(source.filesDir, "datastore/account.preferences_pb")
            }
            accounts.edit {
                it[stringPreferencesKey("server")] = server
                it[stringPreferencesKey("user")] = user
                it[stringPreferencesKey("namespace")] = namespace
            }
            val widgets = PreferenceDataStoreFactory.create(scope = preferencesScope) {
                File(source.filesDir, "datastore/notes-widgets.preferences_pb")
            }
            widgets.edit { it[stringPreferencesKey("tag-42")] = "work/projects" }
        } finally { preferencesScope.coroutineContext[Job]!!.cancelAndJoin() }
        File(source.noBackupFilesDir, "memos-credentials.bin").writeText("Synthetic device-bound ciphertext")
        File(source.cacheDir, "redownloadable").writeText("Disposable download")
        source.getDatabasePath("androidx.work.workdb").writeText("Device-specific worker schedule")

        // Both policies must preserve the complete durable store, while omitting key-bound and
        // redownloadable data. This is a copied-store simulation, not a device backup invocation.
        val cloud = File(root, "cloud").let { StorageContext(base, it) }
        copyPermittedStore(source, cloud, "cloud-backup")
        copyPermittedStore(source, restored, "device-transfer")
        for (target in listOf(cloud, restored)) {
            assertTrue("The notes database is included", target.getDatabasePath("memoize.db").isFile)
            assertFalse(target.getDatabasePath("androidx.work.workdb").exists())
            assertFalse(File(target.noBackupFilesDir, "memos-credentials.bin").exists())
            assertFalse(File(target.cacheDir, "redownloadable").exists())
        }
        val accountStore = AccountStore(restored)
        assertEquals(AccountConfiguration(server, user, namespace), accountStore.configuration())
        assertNull(accountStore.load())
        assertEquals("work/projects", WidgetSettings(restored).tag(42))

        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val graph = AppGraph(restored)
        try {
            val notes = graph.awaitRepository()
            assertEquals(namespace, notes.account)
            assertTrue(requireNotNull(graph.connection).status.value.needsAuthentication)
            val pending = requireNotNull(notes.get("pending"))
            assertEquals("Newer pending source 📝", pending.content)
            assertEquals("Acknowledged base", pending.base?.content)
            assertEquals("In-flight source **literal**", pending.sent?.content)
            assertEquals(4, pending.selectionStart)
            assertEquals(9, pending.selectionEnd)
            assertEquals(17, pending.scroll)
            assertTrue(notes.pending().any { it.noteId == "pending" && it.revision == pending.revision })
            assertEquals("Never published offline source", notes.get("offline-draft")?.content)
            val restoredOriginal = File(requireNotNull(pending.attachments.single().localPath))
            assertTrue("Restored originals belong to this installation", restoredOriginal.toPath().startsWith(restored.filesDir.toPath()))
            assertEquals("Exact restored original 📝", restoredOriginal.readText())
            assertEquals(restoredOriginal.absolutePath, pending.base?.attachments?.single()?.localPath)
            assertEquals(restoredOriginal.absolutePath, pending.sent?.attachments?.single()?.localPath)
            val recovery = notes.recovery("pending").single { it.reason.startsWith("In-flight revision") }
            assertEquals("In-flight source **literal**", recovery.snapshot.content)
            val recoveryOriginal = File(requireNotNull(recovery.snapshot.attachments.single().localPath))
            assertEquals(restoredOriginal.canonicalFile, recoveryOriginal.canonicalFile)
            assertTrue(notes.protectedPaths().contains(restoredOriginal.absolutePath))
            val cleanup = notes.attachmentCleanup().single()
            val removedOriginal = File(requireNotNull(cleanup.attachment.localPath))
            assertTrue(removedOriginal.toPath().startsWith(restored.filesDir.toPath()))
            assertEquals("Removed recovery original", removedOriginal.readText())
            assertTrue(notes.protectedPaths().contains(removedOriginal.absolutePath))
        } finally {
            graph.scope.coroutineContext[Job]!!.cancelAndJoin()
            graph.repository.value.close()
            Dispatchers.resetMain()
            root.deleteRecursively()
        }
    }

    private fun copyPermittedStore(source: StorageContext, target: StorageContext, mode: String) {
        val includes = mutableListOf<Pair<String, String>>()
        val excludes = mutableListOf<Pair<String, String>>()
        source.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            var active = false
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == mode) active = true
                if (xml.eventType == XmlPullParser.END_TAG && xml.name == mode) active = false
                if (active && xml.eventType == XmlPullParser.START_TAG && xml.name in setOf("include", "exclude")) {
                    val rule = xml.getAttributeValue(null, "domain") to xml.getAttributeValue(null, "path")
                    (if (xml.name == "include") includes else excludes) += rule
                }
                xml.next()
            }
        }
        val domains = mapOf("file" to source.filesDir, "database" to source.getDatabasePath("memoize.db").parentFile!!)
        for ((domain, directory) in domains) {
            for (file in directory.walkTopDown().filter { it.isFile }) {
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                fun matches(rule: Pair<String, String>): Boolean = rule.first == domain &&
                    (rule.second == "." || relative == rule.second || relative.startsWith("${rule.second}/") ||
                        (domain == "database" && relative in listOf("${rule.second}-wal", "${rule.second}-journal")))
                if ((includes.isEmpty() || includes.any(::matches)) && excludes.none(::matches)) {
                    val destinationRoot = if (domain == "file") target.filesDir else target.getDatabasePath("memoize.db").parentFile!!
                    val destination = File(destinationRoot, relative)
                    destination.parentFile!!.mkdirs()
                    file.copyTo(destination, overwrite = true)
                }
            }
        }
    }

    private class StorageContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root.apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
    }
}
