package com.connor.record.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoRecordEvent.Finalize
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.connor.record.App
import com.connor.record.App.Companion.context
import com.connor.record.MainActivity
import com.connor.record.R
import com.connor.record.databinding.ActivityMainBinding
import com.connor.record.utils.showToast
import com.google.common.util.concurrent.ListenableFuture
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordService : LifecycleService() {

    companion object {
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val TAG = "RecordService"
    }

    private val path = Environment.getExternalStorageDirectory().absolutePath + "/Download/Live/Record"
    private val file = File(path, "state")
    private val serviceFolder = File(path)
    private val serviceFile = File(path, "service")
    private val eventFile = File(path, "event")


    private val preview by lazy { Preview.Builder().build() }

    private lateinit var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private var currentRecording: Recording? = null

    override fun onCreate() {
        super.onCreate()
        if (!serviceFolder.exists()) serviceFolder.mkdirs()
        initNotification()
        initFloat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceFile.writeText("running")
        eventFile.writeText(" ")
        return super.onStartCommand(intent, flags, startId)
    }

    private val getState = flow {
        while (true) {
            val state = if (!file.exists()) "" else file.readText()
            emit(state)
            delay(1000)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    private fun initNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "record_server", "Record Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            val pi = PendingIntent.getActivity(this, 0, intent, 0)
            val notification = NotificationCompat.Builder(this, "record_server")
                .setContentTitle(getString(R.string.server_tips))
                .setSmallIcon(R.drawable.ic_baseline_fiber_manual_record_24)
                .setContentIntent(pi)
                .build()
            startForeground(1, notification)
        }
    }

    private fun initFloat() {
        EasyFloat.with(this)
            .setLayout(R.layout.float_main)
            .setShowPattern(ShowPattern.ALL_TIME)
            .registerCallback {
                show {
                    if (!App.isRunning) {
                        Log.d(TAG, "onStartCommand: Float")
                        val previewView = it.findViewById<PreviewView>(R.id.previewViewFloat)
                        initPreviewView2(previewView)
                        lifecycleScope.launch(Dispatchers.Main) {
                            getState.collectLatest { state ->
                                Log.d(TAG, "onStartCommand: flow $state")
                                when (state) {
                                    "start" -> {
                                        initVideoCapture()
                                    }
                                    "stop" -> {
                                        val recording = currentRecording
                                        if (recording != null) {
                                            recording.stop()
                                            currentRecording = null
                                        }
                                    }
                                }
                            }
                        }
                        App.isRunning = true
                    }
                }
            }.show()
    }

    private fun initPreviewView2(previewView: PreviewView) {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview2(cameraProvider, previewView)
        }, ContextCompat.getMainExecutor(this))
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private fun bindPreview2(cameraProvider : ProcessCameraProvider, previewView: PreviewView) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        var camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview)
    }

    @SuppressLint("MissingPermission")
    private suspend fun initVideoCapture() {

        val cameraProvider = ProcessCameraProvider.getInstance(this).await()

        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture
            )
        } catch (exc: Exception) {
            Log.e("日志", "Use case binding failed", exc)
        }

        val name = "LiveBox-recording-" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.CHINA)
                    .format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            this.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        currentRecording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput).apply {
                withAudioEnabled()
            }.start(ContextCompat.getMainExecutor(this), captureListener)
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        when (event) {
            is VideoRecordEvent.Start -> {
                eventFile.writeText(getString(R.string.start))
            }
            is VideoRecordEvent.Finalize -> {
                val error = event.error
                if (error != Finalize.ERROR_NONE) {
                    eventFile.writeText(getString(R.string.record_error, error))
                    //getString(R.string.record_error, error).showToast()
                } else {
                    eventFile.writeText(getString(R.string.finalize))
                   // getString(R.string.finalize).showToast()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceFile.writeText("stop")
    }
}