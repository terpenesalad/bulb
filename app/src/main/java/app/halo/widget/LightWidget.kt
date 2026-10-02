package app.halo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import app.halo.MainActivity
import app.halo.R
import app.halo.data.Store
import app.halo.light.LightHub
import app.halo.tuya.BulbCodec
import app.halo.tuya.BulbMode
import app.halo.tuya.BulbState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Home-screen widget: one tap toggles the light. */
class LightWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, LightHub.activeController()?.state?.value, ids, manager)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val pending = goAsync()
            LightHub.scope.launch {
                try { toggle(context) } finally { pending.finish() }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "app.halo.widget.TOGGLE"

        suspend fun toggle(context: Context): Boolean {
            val c = LightHub.activeController() ?: return false
            // Use the bulb's real state when we can, so the toggle never goes the wrong way.
            c.refresh()
            val target = !c.state.value.on
            val ok = c.send(BulbCodec.power(c.schema, target))
            refreshAll(context)
            return ok
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LightWidget::class.java))
            if (ids.isNotEmpty()) render(context, LightHub.activeController()?.state?.value, ids, manager)
            runCatching { TileService.requestListeningState(context, ComponentName(context, LightTileService::class.java)) }
        }

        private fun render(context: Context, state: BulbState?, ids: IntArray, manager: AppWidgetManager) {
            val name = Store.value.activeDevice?.name ?: "Set up Halo"
            val views = RemoteViews(context.packageName, R.layout.widget_light)
            views.setTextViewText(R.id.widget_name, name)
            val on = state?.on == true
            views.setTextViewText(R.id.widget_status, statusText(state))
            views.setImageViewResource(R.id.widget_button, if (on) R.drawable.widget_button_on else R.drawable.widget_button_off)
            views.setInt(R.id.widget_icon, "setColorFilter", if (on) Color.parseColor("#FF2A1A05") else Color.parseColor("#FF9A97A0"))

            val toggle = PendingIntent.getBroadcast(context, 0,
                Intent(context, LightWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
            views.setOnClickPendingIntent(R.id.widget_text, open)
            manager.updateAppWidget(ids, views)
        }

        fun statusText(state: BulbState?): String = when {
            state == null -> "Tap to toggle"
            !state.on -> "Off"
            state.mode == BulbMode.COLOUR -> "On · ${(state.value * 100).roundToInt()}% colour"
            state.mode == BulbMode.SCENE -> "On · scene"
            else -> "On · ${(state.brightness * 100).roundToInt()}%"
        }
    }
}

/** Quick Settings tile. */
class LightTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        // Flip immediately for a snappy feel; the real state follows.
        tile.state = if (tile.state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
        LightHub.scope.launch {
            LightWidget.toggle(applicationContext)
            update()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val c = LightHub.activeController()
        val state = c?.state?.value
        tile.label = Store.value.activeDevice?.name ?: "Halo"
        tile.state = when {
            c == null -> Tile.STATE_UNAVAILABLE
            state?.on == true -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = LightWidget.statusText(state)
        tile.updateTile()
    }
}
