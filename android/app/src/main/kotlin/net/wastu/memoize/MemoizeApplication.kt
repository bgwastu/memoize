package net.wastu.memoize

import android.app.Application
import net.wastu.memoize.ui.AppGraph
import net.wastu.memoize.widget.WidgetRefresh

class MemoizeApplication : Application() {
    val graph: AppGraph by lazy {
        AppGraph(this).also { graph ->
            graph.refreshWidgets = { WidgetRefresh.refresh(this) }
            WidgetRefresh.install(graph)
        }
    }
}
