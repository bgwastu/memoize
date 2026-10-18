package net.wastu.memoize.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import net.wastu.memoize.MainActivity
import net.wastu.memoize.MemoizeApplication
import net.wastu.memoize.R
import net.wastu.memoize.data.NoteFilter
import net.wastu.memoize.data.NoteResult
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class WidgetRow(
    val id: String,
    val title: String,
    val excerpt: String,
    val createdAt: Long,
    val pinned: Boolean,
    val hasAttachments: Boolean,
)

private fun List<NoteResult>.widgetRows(): List<WidgetRow> = take(40).map { result -> WidgetRow(
    result.note.id, result.note.title, result.excerpt.trim(), result.note.createdAt,
    result.note.pinned, result.note.attachments.isNotEmpty(),
) }

/** RemoteViews from a bounded prefix of committed, local notes; never waits for a server. */
class MemoizeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(
        DpSize(180.dp, 160.dp), DpSize(260.dp, 200.dp), DpSize(320.dp, 260.dp), DpSize(320.dp, 400.dp),
    ))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val settings = WidgetSettings(context)
        val initialTag = settings.tag(widgetId)
        val graph = (context.applicationContext as MemoizeApplication).graph
        val initialRepository = graph.awaitRepository()
        val initialRows = initialRepository.search(NoteFilter(tag = initialTag)).widgetRows()
        provideContent {
            val tag by remember { settings.observeTag(widgetId) }.collectAsState(initialTag)
            val repository by graph.repository.collectAsState()
            // Updates reuse a live Glance session. Observe durable data inside that session as well.
            key(repository.account, tag) {
                val rows by remember(repository, tag) {
                    repository.observe(NoteFilter(tag = tag)).map { it.widgetRows() }.distinctUntilChanged()
                        .conflate().map { rows -> delay(500); rows }
                }.collectAsState(if (repository === initialRepository && tag == initialTag) initialRows else emptyList())
                GlanceTheme { NotesWidgetContent(rows, tag) }
            }
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetSettings(context).remove(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

@Composable
private fun NotesWidgetContent(notes: List<WidgetRow>, tag: String?) {
    val context = LocalContext.current
    val columns = if (LocalSize.current.width >= 320.dp && context.resources.configuration.fontScale < 1.5f) 2 else 1
    // Header is fixed with quick capture; notes scroll freely below.
    val previewHeight = LocalSize.current.height - 48.dp
    val compact = previewHeight < (108 * context.resources.configuration.fontScale).dp
    val rows = remember(notes, columns) { notes.chunked(columns) }
    Scaffold {
        Column(GlanceModifier.fillMaxSize()) {
            Row(
                GlanceModifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(mainIntent(context, tag))),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        context.getString(R.string.app_name),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        maxLines = 1
                    )
                    if (tag != null) {
                        Spacer(GlanceModifier.width(8.dp))
                        Box(
                            modifier = GlanceModifier
                                .background(GlanceTheme.colors.secondaryContainer)
                                .cornerRadius(8.dp)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "#$tag",
                                maxLines = 1,
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSecondaryContainer,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.ic_add),
                    contentDescription = context.getString(R.string.widget_add),
                    onClick = actionStartActivity(addIntent(context, tag)),
                    modifier = GlanceModifier.size(40.dp),
                    backgroundColor = GlanceTheme.colors.primaryContainer,
                    contentColor = GlanceTheme.colors.onPrimaryContainer
                )
            }
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (notes.isEmpty()) item(itemId = -2L) {
                    Column(
                        GlanceModifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            context.getString(if (tag == null) R.string.widget_empty else R.string.widget_empty_tag),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                            maxLines = 2
                        )
                    }
                }
                items(rows, itemId = { stableRowId(it.first().id) }) { row ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        row.forEachIndexed { index, note ->
                            if (index > 0) Spacer(GlanceModifier.width(8.dp))
                            NoteWidgetCard(note, compact, GlanceModifier.defaultWeight())
                        }
                        if (row.size < columns) {
                            Spacer(GlanceModifier.width(8.dp))
                            Spacer(GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteWidgetCard(note: WidgetRow, compact: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()))
    val visibleDate = if (compact) DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault())) else date
    Column(
        modifier
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .clickable(actionStartActivity(noteIntent(context, note.id)))
            .padding(if (compact) 8.dp else 12.dp)
            .semantics { contentDescription = "${note.title}, $date" }
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title,
                maxLines = if (compact) 2 else 3,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            if (note.pinned) {
                Spacer(GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_pin),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    modifier = GlanceModifier.size(14.dp)
                )
            }
        }
        if (!compact && note.excerpt.isNotEmpty()) {
            Text(
                note.excerpt,
                maxLines = 4,
                modifier = GlanceModifier.padding(top = 6.dp),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp)
            )
        }
        Row(GlanceModifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                visibleDate,
                maxLines = if (compact) 1 else 2,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.defaultWeight()
            )
            if (note.hasAttachments) {
                Spacer(GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(R.drawable.ic_attach),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.size(12.dp)
                )
            }
        }
    }
}

private fun stableRowId(id: String): Long = ByteBuffer.wrap(
    MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)),
).long and Long.MAX_VALUE

private fun noteIntent(context: Context, id: String) = Intent(context, MainActivity::class.java).apply {
    data = Uri.Builder().scheme("memoize").authority("note").appendPath(id).build()
    putExtra(MainActivity.EXTRA_NOTE_ID, id)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

private fun mainIntent(context: Context, tag: String? = null) = Intent(context, MainActivity::class.java).apply {
    action = Intent.ACTION_MAIN
    data = if (tag != null) {
        Uri.Builder().scheme("memoize").authority("tag").appendPath(tag).build()
    } else {
        "memoize://main".toUri()
    }
    if (tag != null) putExtra(MainActivity.EXTRA_TAG, tag)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}

private fun addIntent(context: Context, tag: String? = null) = Intent(context, MainActivity::class.java).apply {
    action = MainActivity.ACTION_CREATE_NOTE
    data = if (tag != null) {
        Uri.Builder().scheme("memoize").authority("create").appendPath(tag).build()
    } else {
        "memoize://create".toUri()
    }
    putExtra(MainActivity.EXTRA_CREATE_NOTE, true)
    if (tag != null) putExtra(MainActivity.EXTRA_TAG, tag)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
