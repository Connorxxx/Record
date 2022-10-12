package com.connor.record.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.connor.record.MainActivity
import com.connor.record.R
import com.connor.record.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordService : LifecycleService() {

    companion object {
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val TAG = "RecordService"
    }

    private val path = Environment.getExternalStorageDirectory().absolutePath + "/Download/Logs"

    private val preview by lazy { Preview.Builder().build() }

    private var currentRecording: Recording? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ktor_server", "Ktor Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, 0)
        val notification = NotificationCompat.Builder(this, "ktor_server")
            .setContentTitle("Ktor server is running")
            .setContentText("You could disable it notification")
            .setSmallIcon(R.drawable.ic_baseline_fiber_manual_record_24)
            .setContentIntent(pi)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleScope.launch (Dispatchers.IO){
            val file = File(path, "state")
            var oldState = file.readText()
            Log.d(TAG, "onStartCommand: old $oldState")
            while (true) {
                delay(1000)
                val state = file.readText()
                Log.d(TAG, "onStartCommand: new $state")
               if (state != oldState) {
                   Log.d(TAG, "onStartCommand: if")
                   when (file.readText()) {
                       "start" -> {
                           launch(Dispatchers.Main) {
                               initVideoCapture()
                           }
                       }
                       "stop" -> {
                           val recording = currentRecording
                           if (recording != null) {
                               recording.stop()
                               currentRecording = null
                           }
                       }
                   }
               } else oldState = file.readText()
            }
        }
        return super.onStartCommand(intent, flags, startId)
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


        val name = "CameraX-recording-" +
                SimpleDateFormat(FILENAME_FORMAT, Locale.US)
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
            is VideoRecordEvent.Finalize -> {
                "Ok".showToast()
            }
        }
    }
}