package local.silverfishwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle

/**
 * A tap on the widget, showing nothing itself: leaves Silverfish a note saying what to open (its
 * screen, the last book, or the home screen) and opens KOReader, where Silverfish reads the note.
 * Without write access to the folder it just opens KOReader.
 */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        Haptics.widgetTick(this)
        val action = when (Style.load(this, id).tap) {
            "book" -> "livro"
            "home" -> "inicio"
            else -> "silverfish"
        }
        Folder.forWidget(this, id)?.let { Folder.leaveNote(this, it, action) }
        packageManager.getLaunchIntentForPackage(KOREADER)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    companion object {
        const val KOREADER = "org.koreader.launcher"
    }
}
