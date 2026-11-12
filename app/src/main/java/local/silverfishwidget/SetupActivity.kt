package local.silverfishwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, in Obsidian Widget's style: for a widget being added or changed (its folder, light or
 * dark, background opacity), with a live preview on the wallpaper; and from the launcher (the folder
 * new widgets start with, and instant updates).
 */
class SetupActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val forWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out of the first setup doesn't add the widget.
        if (forWidget) setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        // Already on the home screen: it has a folder or a style of its own (widgets from before styles have only a folder).
        val existing = forWidget && (getSharedPreferences("style", MODE_PRIVATE).contains("w$widgetId.mode") ||
            getSharedPreferences("folders", MODE_PRIVATE).contains("w$widgetId"))
        enableEdgeToEdge()
        setContent { AppTheme { Screen(forWidget, existing) } }
    }

    private suspend fun save(folder: Uri, style: Style, minutes: Int) {
        Refresh.setMinutes(this, minutes)
        val forWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (forWidget) {
            Folder.setForWidget(this, widgetId, folder)
            Style.save(this, widgetId, style)
        } else {
            Folder.setDefault(this, folder)
        }
        Refresh.schedule(this)
        withContext(Dispatchers.IO) {
            val mgr = AppWidgetManager.getInstance(this@SetupActivity)
            if (forWidget) PetWidget.update(this@SetupActivity, mgr, widgetId) else PetWidget.updateAll(this@SetupActivity)
        }
        if (forWidget) setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    @Composable
    private fun Screen(forWidget: Boolean, existing: Boolean) {
        val scope = rememberCoroutineScope()
        var folder by remember { mutableStateOf(if (forWidget) Folder.forWidget(this, widgetId) else Folder.default(this)) }
        var check by remember { mutableStateOf<Folder.Check?>(null) }
        var pet by remember { mutableStateOf<Pet?>(null) }
        val start = remember { if (forWidget) Style.load(this, widgetId) else Style() }
        var mode by remember { mutableStateOf(start.mode) }
        var opacity by remember { mutableIntStateOf(start.opacity) }
        var instant by remember { mutableStateOf(Refresh.canInstant()) }
        var every by remember { mutableIntStateOf(Refresh.minutes(this)) }

        // Back from the All files access screen: show whether it was turned on.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) instant = Refresh.canInstant() }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }

        val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching { Folder.take(this, uri) }
                folder = uri
            }
        }
        LaunchedEffect(folder) {
            val tree = folder ?: return@LaunchedEffect
            check = null
            pet = null
            withContext(Dispatchers.IO) {
                val c = Folder.check(this@SetupActivity, tree)
                val p = if (c == Folder.Check.OK) Pet.parse(Folder.readState(this@SetupActivity, tree)) else null
                check = c
                pet = p
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = {
                    PageHeader(
                        when {
                            !forWidget -> getString(R.string.app_name)
                            existing -> "Widget settings"
                            else -> "New widget"
                        },
                        onBack = { finish() },
                    )
                },
                bottomBar = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        val tick = rememberHaptic()
                        Button(
                            onClick = { tick(); folder?.let { f -> scope.launch { save(f, Style(mode, opacity), every) } } },
                            enabled = folder != null && check != null && check != Folder.Check.NOT_SILVERFISH,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = noteColor(),
                            ),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(
                                when {
                                    !forWidget -> "Done"
                                    existing -> "Done"
                                    else -> "Add widget"
                                },
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
            ) { pad ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (forWidget) Preview(pet, Style(mode, opacity))

                    SectionCard("KOReader location", R.drawable.ic_folder) {
                        val found = check == Folder.Check.OK && pet != null
                        NavRow(
                            folder?.let(Folder::displayPath) ?: "Choose folder",
                            status(folder, check, pet) ?: "KOReader folder",
                            R.drawable.ic_folder,
                            onClick = { pickFolder.launch(folder ?: Folder.pickerStart()) },
                            subtitleIcon = if (found) R.drawable.ic_check else null,
                        )
                    }

                    if (forWidget) SectionCard("Appearance", R.drawable.ic_palette) {
                        SettingLabel("Light or dark")
                        PillSegmented(Style.MODES, mode, { it.replaceFirstChar(Char::uppercase) }) { mode = it }
                        SettingLabel("Background opacity", value = "$opacity%")
                        StepSlider(opacity, 0..100, step = 5) { opacity = it }
                    }

                    SectionCard("Updates", R.drawable.ic_refresh) {
                        SettingLabel("Redraw every", "Belly, mood and sleep catch up even when you're not reading.")
                        PillSegmented(Refresh.CHOICES, every, { if (it < 60) "${it}m" else "${it / 60}h" }) { every = it }
                        // Android's own switch for All files access: on lets the widget hear Silverfish save.
                        SwitchRow("Instant updates", "Redraw as soon as Silverfish saves. Needs All files access.", instant) {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    }
                }
            }
        }
    }

    private fun status(folder: Uri?, check: Folder.Check?, pet: Pet?): String? = when {
        folder == null || check == null -> null
        check == Folder.Check.OK && pet != null -> "${pet.name ?: "Silverfish"} found"
        check == Folder.Check.OK -> "Can't read the pet's data"
        check == Folder.Check.NO_DATA_YET -> "No pet yet: read a page in KOReader"
        else -> "Silverfish not found here"
    }

    /** The widget as it will look, at its own size and shape, on the wallpaper. */
    @Composable
    private fun Preview(pet: Pet?, style: Style) {
        // A standard 4x2 widget, not the one on the home screen: just how it looks.
        val d = resources.displayMetrics.density
        val w = (340 * d).toInt()
        val h = (170 * d).toInt()
        val bitmap: Bitmap = remember(pet, style.mode, style.opacity) {
            val now = System.currentTimeMillis() / 1000
            if (pet != null) Render.pet(this, w, h, pet, now, style)
            else Render.message(this, w, h, "Choose your KOReader folder", style)
        }
        Image(
            bitmap.asImageBitmap(), contentDescription = "Widget preview",
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).aspectRatio(w.toFloat() / h),
        )
    }
}
