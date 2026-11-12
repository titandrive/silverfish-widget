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
                for ((wDp, hDp) in listOf(473 to 101, 266 to 110, 473 to 190, 473 to 386, 180 to 180, 300 to 300)) {
                    val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(out).apply { drawColor(Color.BLACK); drawBitmap(Render.pet(ctx, w, h, pet, now), 0f, 0f, null) }
                    File(dir, "${wDp}x$hDp.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
