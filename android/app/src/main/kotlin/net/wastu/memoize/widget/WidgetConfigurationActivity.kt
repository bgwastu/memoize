package net.wastu.memoize.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import net.wastu.memoize.MemoizeApplication
import net.wastu.memoize.R
import net.wastu.memoize.ui.IconAction
import net.wastu.memoize.ui.MemoizeTheme

/** Optional launcher configuration; each placed widget keeps its own hierarchical tag. */
class WidgetConfigurationActivity : ComponentActivity() {
    private var selectedTag by mutableStateOf<String?>(null)
    private var tags by mutableStateOf(emptyList<String>())
    private var loading by mutableStateOf(true)
    private var ready by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(RESULT_CANCELED, result)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(this)
                .getAppWidgetInfo(id)?.provider != ComponentName(this, MemoizeWidgetReceiver::class.java)) {
            finish()
            return
        }
        val settings = WidgetSettings(this)
        val restoredSelection = savedInstanceState?.containsKey("selected-tag") == true
        if (restoredSelection) selectedTag = savedInstanceState.getString("selected-tag")
        var selectionLoaded = restoredSelection
        fun load() {
            loading = true
            error = null
            lifecycleScope.launch {
                try {
                    if (!selectionLoaded) {
                        selectedTag = settings.tag(id)
                        selectionLoaded = true
                    }
                    val repository = (application as MemoizeApplication).graph.awaitRepository()
                    tags = (repository.tags() + listOfNotNull(selectedTag)).distinct().sorted()
                    ready = true
                } catch (canceled: CancellationException) { throw canceled
                } catch (_: Exception) {
                    error = "Could not load widget settings."
                } finally { loading = false }
            }
        }
        load()
        setContent {
            MemoizeTheme {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("Notes widget") }, navigationIcon = {
                        IconAction(R.drawable.ic_back, "Cancel widget setup", action = { finish() })
                    })
                }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                        Text("Choose a tag", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp))
                        if (loading) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        } else if (!ready) Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { load() }) { Text("Retry") }
                        } else LazyColumn(Modifier.weight(1f).selectableGroup()) {
                            item {
                                TagChoice("All notes", selectedTag == null) { selectedTag = null }
                            }
                            items(tags, key = { it }) { tag ->
                                TagChoice("#$tag", selectedTag == tag) { selectedTag = tag }
                            }
                        }
                        if (ready) error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            Button(enabled = ready && !loading && !saving, onClick = {
                                saving = true
                                lifecycleScope.launch {
                                    try {
                                        settings.setTag(id, selectedTag)
                                        MemoizeWidget().update(this@WidgetConfigurationActivity,
                                            GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(id))
                                        setResult(RESULT_OK, result)
                                        finish()
                                    } catch (canceled: CancellationException) { throw canceled
                                    } catch (_: Exception) {
                                        saving = false
                                        error = "Could not update this widget. Try saving again."
                                    }
                                }
                            }) { Text(if (saving) "Saving…" else "Save") }
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected-tag", selectedTag)
        super.onSaveInstanceState(outState)
    }
}

@androidx.compose.runtime.Composable
private fun TagChoice(label: String, selected: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = select),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
