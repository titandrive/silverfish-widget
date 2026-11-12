package local.silverfishwidget

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * When the widgets redraw:
 *  - Instantly when Silverfish saves, if All files access is granted: Android wakes the app a
 *    moment after a file changes on shared storage (from its file index); it only tells apps that
 *    can see the file. Redraws only if widget/estado.json actually changed.
 *  - Every 15 minutes regardless (the shortest Android allows): belly and mood keep falling and the
 *    pet falls asleep at night between saves, as Silverfish works them out whenever it's looked at.
 *    This also picks up saves when instant updates are off.
 */
object Refresh {
    private const val CLOCK = "clock"
    private const val INSTANT = "instant"

    fun canInstant() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Starts (or keeps) both kinds of refresh; safe to call any time. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(
            CLOCK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Run>(15, TimeUnit.MINUTES).setInputData(workDataOf("all" to true)).build(),
        )
        armInstant(ctx, rearm = false)
    }

    fun cancel(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(CLOCK)
        wm.cancelUniqueWork(INSTANT)
    }

    /** Fires once on the next change on shared storage; [Run] arms it again. */
    private fun armInstant(ctx: Context, rearm: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!canInstant()) {
            wm.cancelUniqueWork(INSTANT)
            return
        }
        wm.enqueueUniqueWork(
            INSTANT,
            if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Run>()
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                        .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                        .build(),
                )
                .setInputData(workDataOf("all" to false))
                .build(),
        )
    }

    class Run(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val all = inputData.getBoolean("all", true)
            // The clock redraws everything; a file change redraws only widgets whose data changed.
            PetWidget.updateAll(applicationContext, onlyIfChanged = !all)
            if (!all) armInstant(applicationContext, rearm = true)
            return Result.success()
        }
    }
}
