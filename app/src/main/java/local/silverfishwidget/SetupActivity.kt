package local.silverfishwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Setup, for a widget being added (pick its folder, then Add) or from the launcher (the folder new
 * widgets start with, and instant updates).
 */
class SetupActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var folder: Uri? = null
    private var check: Folder.Check? = null

    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var instantView: TextView
    private lateinit var instantButton: Button
    private lateinit var doneButton: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val forWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (forWidget) {
            // Backing out without finishing setup doesn't add the widget.
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
        folder = if (forWidget) Folder.forWidget(this, widgetId) else Folder.default(this)
        buildUi(forWidget)
        refreshFolder()
    }

    override fun onResume() {
        super.onResume()
        refreshInstant()  // back from the All files access screen
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, top: Int = 0) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun button(label: String, top: Int = 8, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
        setOnClickListener { onClick() }
    }

    private fun buildUi(forWidget: Boolean) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        col.addView(text(24f).apply { text = getString(R.string.app_name) })
        col.addView(text(15f, 12).apply {
            text = "Choose your KOReader folder, or the silverfish.koplugin folder inside it. " +
                "Silverfish writes the pet's data there every time it saves."
        })

        col.addView(text(13f, 24).apply { text = "FOLDER"; alpha = 0.6f })
        pathView = text(16f, 4).also(col::addView)
        statusView = text(14f, 8).also(col::addView)
        col.addView(button("Choose folder") { pick() })

        col.addView(text(13f, 24).apply { text = "INSTANT UPDATES"; alpha = 0.6f })
        instantView = text(14f, 4).also(col::addView)
        instantButton = button("Allow All files access") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }.also(col::addView)

        doneButton = button(if (forWidget) "Add widget" else "Done", 32) { finishSetup() }.also(col::addView)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder ?: Folder.pickerStart())
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK)
    }

    @Deprecated("Plain Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != PICK || resultCode != RESULT_OK || tree == null) return
        runCatching { Folder.take(this, tree) }
        folder = tree
        refreshFolder()
    }

    /** Shows the folder and what was found in it (read off the main thread). */
    private fun refreshFolder() {
        val tree = folder
        pathView.text = tree?.let(Folder::displayPath) ?: "None chosen"
        if (tree == null) {
            statusView.text = ""
            check = null
            doneButton.isEnabled = false
            return
        }
        statusView.text = "Looking…"
        doneButton.isEnabled = false
        thread {
            val result = Folder.check(this, tree)
            val pet = if (result == Folder.Check.OK) Pet.parse(Folder.readState(this, tree)) else null
            runOnUiThread {
                if (tree != folder) return@runOnUiThread
                check = result
                statusView.text = when (result) {
                    Folder.Check.OK -> pet?.let {
                        val name = it.name ?: "Your Silverfish"
                        val ago = DateUtils.getRelativeTimeSpanString(it.savedAt * 1000)
                        "Found $name (${it.stageTitle}), saved $ago."
                    } ?: "Found Silverfish's data, but couldn't read it."
                    Folder.Check.NO_DATA_YET ->
                        "Found Silverfish, but it hasn't saved widget data yet. Open KOReader and read a page; " +
                            "the widget shows the pet as soon as it saves."
                    Folder.Check.NOT_SILVERFISH ->
                        "This isn't the KOReader folder or the Silverfish plugin folder."
                }
                doneButton.isEnabled = result != Folder.Check.NOT_SILVERFISH
            }
        }
    }

    private fun refreshInstant() {
        val on = Refresh.canInstant()
        instantView.text = if (on) "On: the widget redraws a moment after Silverfish saves."
        else "Off: the widget picks up Silverfish's saves within 15 minutes. With All files access " +
            "it redraws a moment after each save."
        instantButton.visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        Refresh.schedule(this)
    }

    private fun finishSetup() {
        val tree = folder ?: return
        val forWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (forWidget) Folder.setForWidget(this, widgetId, tree) else Folder.setDefault(this, tree)
        Refresh.schedule(this)
        val app = applicationContext
        val id = widgetId
        thread {
            if (forWidget) PetWidget.update(app, AppWidgetManager.getInstance(app), id) else PetWidget.updateAll(app)
        }
        if (forWidget) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    private companion object {
        const val PICK = 1
    }
}
