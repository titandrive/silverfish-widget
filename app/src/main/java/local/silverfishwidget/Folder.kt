package local.silverfishwidget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * The folder a widget reads, picked through the Storage Access Framework: either KOReader's own
 * folder or the Silverfish plugin folder inside it. Silverfish (our fork, traca_widget.lua) writes
 * widget/estado.json into its plugin folder each time it saves; this finds and reads that file.
 */
object Folder {
    private const val PREFS = "folders"
    private const val PLUGIN_DIR = "silverfish.koplugin"

    /** Where the pet's data can be, from the picked folder. */
    private val STATE_PATHS = listOf(
        listOf("widget", "estado.json"),                              // picked the plugin folder
        listOf("plugins", PLUGIN_DIR, "widget", "estado.json"),       // picked the KOReader folder
    )

    enum class Check { OK, NO_DATA_YET, NOT_SILVERFISH }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forWidget(ctx: Context, id: Int): Uri? =
        prefs(ctx).getString("w$id", null)?.let(Uri::parse) ?: default(ctx)

    fun setForWidget(ctx: Context, id: Int, tree: Uri) {
        prefs(ctx).edit().putString("w$id", tree.toString()).putString("default", tree.toString()).apply()
    }

    fun removeWidget(ctx: Context, id: Int) {
        prefs(ctx).edit().remove("w$id").apply()
    }

    fun default(ctx: Context): Uri? = prefs(ctx).getString("default", null)?.let(Uri::parse)

    fun setDefault(ctx: Context, tree: Uri) {
        prefs(ctx).edit().putString("default", tree.toString()).apply()
    }

    /** Keeps read access to a folder the user just picked, across restarts. */
    fun take(ctx: Context, tree: Uri) {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Where the folder is on the device, e.g. "koreader/plugins/silverfish.koplugin". */
    fun displayPath(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        return if (volume == "primary") path.ifEmpty { "Internal storage" } else "$volume/$path"
    }

    /** Suggested start for the folder picker: KOReader's folder on internal storage. */
    fun pickerStart(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:koreader",
    )

    /** The pet's data as JSON text, or null when it isn't there (or the folder can't be read). */
    fun readState(ctx: Context, tree: Uri): String? {
        val doc = findState(ctx, tree) ?: return null
        return runCatching {
            ctx.contentResolver.openInputStream(doc)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onFailure { android.util.Log.w("SilverfishWidget", "Can't open $doc", it) }.getOrNull()
    }

    /** Whether the picked folder works: has the pet's data, is Silverfish without data yet, or neither. */
    fun check(ctx: Context, tree: Uri): Check {
        if (findState(ctx, tree) != null) return Check.OK
        val root = rootId(tree) ?: return Check.NOT_SILVERFISH
        val isPlugin = child(ctx, tree, root, "traca_estado.lua") != null
        val plugins = child(ctx, tree, root, "plugins")
        val hasPlugin = plugins != null && child(ctx, tree, plugins, PLUGIN_DIR) != null
        return if (isPlugin || hasPlugin) Check.NO_DATA_YET else Check.NOT_SILVERFISH
    }

    private fun rootId(tree: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()

    private fun findState(ctx: Context, tree: Uri): Uri? {
        val root = rootId(tree) ?: return null
        for (path in STATE_PATHS) {
            var id: String? = root
            for (name in path) {
                id = child(ctx, tree, id ?: break, name)
            }
            if (id != null) return DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
        return null
    }

    /** The document id of [name] directly inside [parentId], or null. */
    private fun child(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return runCatching {
            ctx.contentResolver.query(
                children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name) return@use c.getString(0)
                null
            }
        }.getOrNull()
    }
}
