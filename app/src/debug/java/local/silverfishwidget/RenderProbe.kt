package local.silverfishwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File

/**
 * Debug builds only: renders the widget at a range of sizes into
 * Android/data/local.silverfishwidget/files/probe/, to check layouts without resizing on the home screen.
 */
class RenderProbe : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val dir = File(ctx.getExternalFilesDir(null), "probe").apply { mkdirs() }
                val folder = Folder.default(ctx)
                val pet = Pet.parse(folder?.let { Folder.readState(ctx, it) }) ?: return@Thread
                val d = ctx.resources.displayMetrics.density
                val now = System.currentTimeMillis() / 1000
                for ((wDp, hDp) in listOf(145 to 380, 120 to 250, 180 to 300, 266 to 386, 300 to 300)) {
                    val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(Render.pet(ctx, w, h, pet, now), 0f, 0f, null) }
                    File(dir, "${wDp}x$hDp.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                // Faces over time: now, a day and a bit later (hungry: sad), five days later (cocoon), 01:00 tonight (asleep).
                val tonight = java.util.Calendar.getInstance().apply {
                    timeInMillis = now * 1000; add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 1); set(java.util.Calendar.MINUTE, 0)
                }.timeInMillis / 1000
                for ((name, t) in listOf("t0-now" to now, "t1-30h" to now + 30 * 3600, "t2-5d" to now + 5 * 86400, "t3-night" to tonight)) {
                    val w = (300 * d).toInt(); val h = (300 * d).toInt()
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(Render.pet(ctx, w, h, pet, t), 0f, 0f, null) }
                    File(dir, "$name-${pet.face(t)}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                // Light, and dark at 60% over a stand-in wallpaper colour, to check the theme and opacity.
                for ((name, style) in listOf("light" to Style("light", 100), "dark60" to Style("dark", 60))) {
                    for ((wDp, hDp) in listOf(473 to 190, 300 to 300)) {
                        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
                        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(out).apply { drawColor(0xFF5A2A6E.toInt()); drawBitmap(Render.pet(ctx, w, h, pet, now, style), 0f, 0f, null) }
                        File(dir, "${wDp}x$hDp-$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
