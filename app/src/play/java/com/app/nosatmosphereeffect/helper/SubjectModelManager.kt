package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.Closeable

object SubjectModelBuild {
    val delivery = SubjectModelDelivery.GOOGLE_PLAY_SERVICES
}

/** Starts a Google Play services module download only after an explicit tap. */
class SubjectModelManager(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val moduleClient = ModuleInstall.getClient(appContext)

    // Only made for a download: a model client loads the model's native code,
    // which a version Play services ships broken can crash the app with.
    private val segmenterHolder = lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().build())
    }
    private val segmenter get() = segmenterHolder.value
    private val main = Handler(Looper.getMainLooper())

    private var listener: InstallStatusListener? = null
    @Volatile private var closed = false

    /**
     * Reads the installed state without requesting or downloading anything,
     * and without loading the model: from its version, read off the main
     * thread. A broken version reads as BROKEN.
     */
    fun checkAvailability(onState: (SubjectModelState) -> Unit) {
        if (closed) return
        Thread({
            val phase = runCatching { SubjectMaskExtractor.modelAvailability(appContext) }
                .getOrDefault(SubjectModelPhase.FAILED)
            main.post {
                if (!closed) {
                    onState(SubjectModelState(phase, if (phase == SubjectModelPhase.READY) 100 else null))
                }
            }
        }, "SubjectModelCheck").apply { isDaemon = true }.start()
    }

    fun download(onState: (SubjectModelState) -> Unit) {
        if (closed) return
        unregisterListener()
        onState(SubjectModelState(SubjectModelPhase.DOWNLOADING))

        val statusListener = InstallStatusListener { update ->
            if (closed) return@InstallStatusListener
            when (update.installState) {
                ModuleInstallStatusUpdate.InstallState.STATE_PENDING -> {
                    onState(SubjectModelState(SubjectModelPhase.DOWNLOADING))
                }
                ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                    onState(
                        SubjectModelState(
                            SubjectModelPhase.DOWNLOADING,
                            update.progressInfo?.let { progress ->
                                val total = progress.totalBytesToDownload
                                if (total > 0L) {
                                    ((progress.bytesDownloaded.toDouble() / total.toDouble()) * 100.0)
                                        .toInt()
                                        .coerceIn(0, 100)
                                } else {
                                    null
                                }
                            }
                        )
                    )
                }
                ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING -> {
                    onState(SubjectModelState(SubjectModelPhase.INSTALLING))
                }
                ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOAD_PAUSED -> {
                    onState(SubjectModelState(SubjectModelPhase.PAUSED))
                }
                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                    onState(SubjectModelState(SubjectModelPhase.READY, 100))
                    unregisterListener()
                }
                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED,
                ModuleInstallStatusUpdate.InstallState.STATE_FAILED -> {
                    onState(SubjectModelState(SubjectModelPhase.FAILED))
                    unregisterListener()
                }
            }
        }
        listener = statusListener

        val request = ModuleInstallRequest.newBuilder()
            .addApi(segmenter)
            .setListener(statusListener)
            .build()

        moduleClient.installModules(request)
            .addOnSuccessListener { response ->
                if (closed) return@addOnSuccessListener
                if (response.areModulesAlreadyInstalled()) {
                    onState(SubjectModelState(SubjectModelPhase.READY, 100))
                    unregisterListener()
                }
            }
            .addOnFailureListener {
                if (!closed) onState(SubjectModelState(SubjectModelPhase.FAILED))
                unregisterListener()
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        unregisterListener()
        if (segmenterHolder.isInitialized()) segmenter.close()
    }

    private fun unregisterListener() {
        val activeListener = listener ?: return
        listener = null
        moduleClient.unregisterListener(activeListener)
    }
}
