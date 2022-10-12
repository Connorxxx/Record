package com.connor.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.connor.record.databinding.ActivityMainBinding
import com.connor.record.service.RecordService
import com.connor.record.utils.showToast
import com.connor.record.utils.startService
import com.google.common.util.concurrent.ListenableFuture
import com.permissionx.guolindev.PermissionX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(R.layout.activity_main) {

    companion object {
        const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private val preview by lazy { Preview.Builder().build() }

    private lateinit var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private var currentRecording: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initPermissionX()
        initPreviewView()
        startService<RecordService>(this) {}
        binding.btnStart.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                initVideoCapture()
            }
        }
        binding.btnStop.setOnClickListener {
            val recording = currentRecording
            if (recording != null) {
                recording.stop()
                currentRecording = null
            }
        }
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

    private fun initPreviewView() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
        binding.previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private fun bindPreview(cameraProvider : ProcessCameraProvider) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(binding.previewView.surfaceProvider)

        var camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview)

    }

    private fun initPermissionX() {
        PermissionX.init(this)
            .permissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList, "Core fundamental are based on these permissions",
                    "OK", "Cancel"
                )
            }
            .request { allGranted, _, deniedList ->
                if (!allGranted) {
                    "These permissions are denied: $deniedList".showToast()
                }
            }
    }
}