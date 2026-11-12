package local.silverfishwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class PetWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Refresh.schedule(ctx)
        drawAsync(ctx, ids)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        drawAsync(ctx, intArrayOf(id))  // resized: lay out again
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) {
            Folder.removeWidget(ctx, id)
            Style.remove(ctx, id)
            Seen.forget(ctx, id)
        }
    }

    override fun onEnabled(ctx: Context) = Refresh.schedule(ctx)

    override fun onDisabled(ctx: Context) = Refresh.cancel(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Refresh.schedule(ctx)
            drawAsync(ctx, allIds(ctx))
        }
        if (intent.action == ACTION_PET) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            Haptics.widgetTick(ctx)
            val pending = goAsync()
            val app = ctx.applicationContext
            thread {
                try {
                    // Noted for Silverfish to apply (it owns the pet's state), and shown straight away.
                    Folder.forWidget(app, id)?.let { Folder.addPet(app, it, System.currentTimeMillis() / 1000) }
                    updateAll(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /** Reading through the storage framework can take a moment; never on the broadcast's thread. */
    private fun drawAsync(ctx: Context, ids: IntArray) {
        val pending = goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                val mgr = AppWidgetManager.getInstance(app)
                for (id in ids) update(app, mgr, id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val KOREADER = "org.koreader.launcher"

        fun allIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PetWidget::class.java))

        /** Redraws every widget; with [onlyIfChanged], only those whose data file changed since last drawn. */
        fun updateAll(ctx: Context, onlyIfChanged: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in allIds(ctx)) update(ctx, mgr, id, onlyIfChanged)
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int, onlyIfChanged: Boolean = false) {
            val folder = Folder.forWidget(ctx, id)
            val text = folder?.let { Folder.readState(ctx, it) }
            if (onlyIfChanged && text != null && !Seen.changed(ctx, id, text)) return
            val (w, h) = sizePx(ctx, mgr, id)
            val now = System.currentTimeMillis() / 1000
            val pet = Pet.parse(text, folder?.let { Folder.pets(ctx, it) }.orEmpty())
            val style = Style.load(ctx, id)
            val bitmap = when {
                folder == null -> Render.message(ctx, w, h, "Tap to choose the Silverfish folder", style)
                pet != null -> Render.pet(ctx, w, h, pet, now, style)
                Folder.check(ctx, folder) == Folder.Check.NO_DATA_YET ->
                    Render.message(ctx, w, h, "Waiting for Silverfish: open KOReader and read a page", style)
                else -> Render.message(ctx, w, h, "Can't find Silverfish here. Tap to choose its folder", style)
            }
            if (text != null) Seen.record(ctx, id, text)
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.pet, bitmap)
            views.setOnClickPendingIntent(R.id.root, tapIntent(ctx, id, ready = pet != null))
            petArea(ctx, views, id, if (pet != null) Render.petBounds else null)
            mgr.updateAppWidget(id, views)
        }

        private const val ACTION_PET = "local.silverfishwidget.PET"

        /**
         * The tap that gives affection, over where the creature was drawn (a bit larger, for a finger).
         * Placing it takes Android 12; before that the whole widget opens KOReader.
         */
        private fun petArea(ctx: Context, views: RemoteViews, id: Int, bounds: android.graphics.RectF?) {
            if (bounds == null || android.os.Build.VERSION.SDK_INT < 31) {
                views.setViewVisibility(R.id.pet_area, android.view.View.GONE)
                return
            }
            val grow = maxOf(bounds.width(), bounds.height()) * 0.15f
            val px = android.util.TypedValue.COMPLEX_UNIT_PX
            views.setViewLayoutMargin(R.id.pet_area, RemoteViews.MARGIN_LEFT, maxOf(0f, bounds.left - grow), px)
            views.setViewLayoutMargin(R.id.pet_area, RemoteViews.MARGIN_TOP, maxOf(0f, bounds.top - grow), px)
            views.setViewLayoutWidth(R.id.pet_area, bounds.width() + 2 * grow, px)
            views.setViewLayoutHeight(R.id.pet_area, bounds.height() + 2 * grow, px)
            views.setViewVisibility(R.id.pet_area, android.view.View.VISIBLE)
            val intent = Intent(ctx, PetWidget::class.java).setAction(ACTION_PET).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setOnClickPendingIntent(
                R.id.pet_area,
                PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }

        /** Opens KOReader (via [TapActivity], which tells Silverfish what to show) once the widget works; until then, its setup. */
        private fun tapIntent(ctx: Context, id: Int, ready: Boolean): PendingIntent {
            val hasKoreader = ctx.packageManager.getLaunchIntentForPackage(KOREADER) != null
            val intent = (if (ready && hasKoreader) Intent(ctx, TapActivity::class.java) else Intent(ctx, SetupActivity::class.java))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivity(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** The widget's size in pixels, as the launcher reports it for the current orientation. */
        fun sizePx(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val wDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val hDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val d = ctx.resources.displayMetrics.density
            return max(1, ((if (wDp > 0) wDp else 250) * d).roundToInt()) to
                max(1, ((if (hDp > 0) hDp else 110) * d).roundToInt())
        }
    }
}

/** What each widget was last drawn from, so a file change elsewhere on storage doesn't redraw it for nothing. */
private object Seen {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("seen", Context.MODE_PRIVATE)
    fun changed(ctx: Context, id: Int, text: String) = prefs(ctx).getInt("w$id", 0) != text.hashCode()
    fun record(ctx: Context, id: Int, text: String) = prefs(ctx).edit().putInt("w$id", text.hashCode()).apply()
    fun forget(ctx: Context, id: Int) = prefs(ctx).edit().remove("w$id").apply()
}
