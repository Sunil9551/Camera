package com.camera.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.camera.app.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private enum class CaptureMode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mediaActionSound: MediaActionSound

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var camera: Camera? = null

    private var currentMode = CaptureMode.PHOTO

    private var isPaused = false
    private var is30Fps = true
    private var isHighResPhoto = false
    private var isFlashOn = false

    private var secondsElapsed = 0
    private var isRedDotVisible = true

    private val handler = Handler(Looper.getMainLooper())
    private val ASPECT_4_3 = Rational(4, 3)

    private val timerRunnable = object : Runnable {
        override fun run() {
            secondsElapsed++
            val hours = secondsElapsed / 3600
            val minutes = (secondsElapsed % 3600) / 60
            val secs = secondsElapsed % 60

            binding.tvTimer.setTextValue(
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
            )

            isRedDotVisible = !isRedDotVisible
            binding.vRedDot.visibility = if (isRedDotVisible) View.VISIBLE else View.INVISIBLE
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor = Executors.newSingleThreadExecutor()

        mediaActionSound = MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }

        binding.timerLayout.visibility = View.GONE
        binding.tvTimer.setTextValue("00:00:00")

        updateModeUI()

        if (allPermissionsGranted()) {
            startCameraForCurrentMode()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        binding.btnRecord.setOnClickListener {
            when (currentMode) {
                CaptureMode.PHOTO -> capturePhoto()
                CaptureMode.VIDEO -> {
                    if (recording == null) {
                        captureVideo()
                    } else {
                        pauseResumeVideo()
                    }
                }
            }
        }

        binding.btnStop.setOnClickListener {
            stopRecordingVideo()
        }

        binding.btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            isFlashOn = false
            binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
            startCameraForCurrentMode()
        }

        binding.btnFlash.setOnClickListener {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                isFlashOn = !isFlashOn
                camera?.cameraControl?.enableTorch(isFlashOn)
                binding.btnFlash.setImageResource(
                    if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                )
            } else {
                Toast.makeText(this, "Flash unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.tvModeCamera.setOnClickListener { switchMode(CaptureMode.PHOTO) }
        binding.tvModeVideo.setOnClickListener { switchMode(CaptureMode.VIDEO) }

        binding.btnZoomIn.setOnClickListener { adjustZoom(0.15f) }
        binding.btnZoomOut.setOnClickListener { adjustZoom(-0.15f) }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val newIs30Fps = prefs.getBoolean(SettingsActivity.KEY_IS_30FPS, true)
        val newIsHighResPhoto = prefs.getBoolean(SettingsActivity.KEY_HIGH_RES_PHOTO, false)

        val settingsChanged = newIs30Fps != is30Fps || newIsHighResPhoto != isHighResPhoto
        is30Fps = newIs30Fps
        isHighResPhoto = newIsHighResPhoto

        if (settingsChanged && recording == null) {
            startCameraForCurrentMode()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCameraForCurrentMode()
            } else {
                Toast.makeText(this, "Camera और Microphone permission जरूरी है", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun switchMode(newMode: CaptureMode) {
        if (recording != null) {
            Toast.makeText(this, "Recording रुकने के बाद mode बदलें", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentMode == newMode) return
        currentMode = newMode
        updateModeUI()
        startCameraForCurrentMode()
    }

    private fun updateModeUI() {
        val isPhoto = currentMode == CaptureMode.PHOTO
        binding.tvAppTitle.text = if (isPhoto) "Camera" else "Video recorder"
        binding.tvModeCamera.setBackgroundResource(if (isPhoto) R.drawable.toggle_selected_bg else 0)
        binding.tvModeVideo.setBackgroundResource(if (!isPhoto) R.drawable.toggle_selected_bg else 0)
        binding.tvModeCamera.setTextColor(if (isPhoto) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        binding.tvModeVideo.setTextColor(if (!isPhoto) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        binding.btnStop.visibility = View.GONE
        setModeToggleEnabled(true)
    }

    private fun setModeToggleEnabled(enabled: Boolean) {
        binding.tvModeCamera.isEnabled = enabled
        binding.tvModeVideo.isEnabled = enabled
        binding.modeToggleContainer.alpha = if (enabled) 1f else 0.4f
    }

    private fun getNextSequentialPhotoFileName(): String {
        val prefs = getSharedPreferences(PREFS_APP_COUNTERS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_PHOTO_COUNTER, 0)
        val fileName = String.format(Locale.US, "Image_%04d", count)
        val nextCount = if (count >= 9999) 0 else count + 1
        prefs.edit().putInt(KEY_PHOTO_COUNTER, nextCount).apply()
        return fileName
    }

    private fun capturePhoto() {
        val imageCapture = this.imageCapture ?: return
        binding.btnRecord.isEnabled = false
        val fileName = getNextSequentialPhotoFileName()

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Images")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
                    binding.btnRecord.isEnabled = true
                    Toast.makeText(baseContext, "Saved: DCIM/Images/$fileName.jpg", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exception: ImageCaptureException) {
                    binding.btnRecord.isEnabled = true
                    Toast.makeText(baseContext, "Photo capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun getNextSequentialFileName(): String {
        val prefs = getSharedPreferences(PREFS_APP_COUNTERS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_VIDEO_COUNTER, 0)
        val fileName = String.format(Locale.US, "Video_Clip_%04d", count)
        val nextCount = if (count >= 9999) 0 else count + 1
        prefs.edit().putInt(KEY_VIDEO_COUNTER, nextCount).apply()
        return fileName
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        binding.btnRecord.isEnabled = false
        val fileName = getNextSequentialFileName()

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Video Clips")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        binding.btnRecord.isEnabled = true
                        isPaused = false
                        binding.btnRecord.setImageResource(R.drawable.ic_pause)
                        binding.btnStop.visibility = View.VISIBLE
                        mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
                        setModeToggleEnabled(false)
                        startTimer()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "Saved: DCIM/Video Clips/$fileName.mp4", Toast.LENGTH_LONG).show()
                        } else {
                            recording?.close()
                            recording = null
                        }
                        binding.btnRecord.isEnabled = true
                        binding.btnRecord.setImageResource(0)
                        binding.btnStop.visibility = View.GONE
                        mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                        setModeToggleEnabled(true)
                        stopTimer()
                    }
                }
            }
    }

    private fun stopRecordingVideo() {
        val curRecording = recording ?: return
        binding.btnRecord.isEnabled = false
        curRecording.stop()
        recording = null
    }

    private fun pauseResumeVideo() {
        val curRecording = recording ?: return
        if (!isPaused) {
            curRecording.pause()
            isPaused = true
            binding.btnRecord.setImageResource(R.drawable.ic_play)
            handler.removeCallbacks(timerRunnable)
            binding.vRedDot.visibility = View.VISIBLE
        } else {
            curRecording.resume()
            isPaused = false
            binding.btnRecord.setImageResource(R.drawable.ic_pause)
            handler.post(timerRunnable)
        }
    }

    private fun startTimer() {
        secondsElapsed = 0
        binding.tvTimer.setTextValue("00:00:00")
        binding.timerLayout.visibility = View.VISIBLE
        isRedDotVisible = true
        binding.vRedDot.visibility = View.VISIBLE
        handler.removeCallbacks(timerRunnable)
        handler.post(timerRunnable)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        secondsElapsed = 0
        binding.tvTimer.setTextValue("00:00:00")
        binding.timerLayout.visibility = View.GONE
        isRedDotVisible = true
        binding.vRedDot.visibility = View.VISIBLE
    }

    private fun adjustZoom(delta: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val newRatio = (zoomState.zoomRatio + delta * zoomState.maxZoomRatio)
            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(newRatio)
    }
            private fun startCameraForCurrentMode() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val recordingFps = if (is30Fps) Range(30, 30) else Range(24, 24)
            val previewFps = if (is30Fps) Range(15, 30) else Range(15, 24)

            // Preview Setup - Target Rotation Landscape (ROTATION_90) पर लॉक है
            val previewBuilder = Preview.Builder()
                .setTargetFrameRate(previewFps)
                .setTargetRotation(Surface.ROTATION_90)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )

            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // ViewPort: 4:3 Landscape विज़न को पोर्ट्रेट UI में बनाए रखने के लिए ROTATION_90
            val viewPort = ViewPort.Builder(ASPECT_4_3, Surface.ROTATION_90)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()

            val useCaseGroupBuilder = UseCaseGroup.Builder().setViewPort(viewPort)

            if (currentMode == CaptureMode.PHOTO) {
                val photoResolutionSelector = if (isHighResPhoto) {
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(8000, 6000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build()
                } else {
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                }

                val imageCaptureBuilder = ImageCapture.Builder()
                    .setResolutionSelector(photoResolutionSelector)
                    .setTargetRotation(Surface.ROTATION_90)

                // Camera2Interop: Photo mode के लिए सेंसर का पूरा हिस्सा (Full FOV) लॉक
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val targetId = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "1" else "0"
                val characteristics = cameraManager.getCameraCharacteristics(targetId)
                val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                val imageExtender = Camera2Interop.Extender(imageCaptureBuilder)
                if (activeArraySize != null) {
                    imageExtender.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, activeArraySize)
                }

                imageCapture = imageCaptureBuilder.build()
                useCaseGroupBuilder.addUseCase(preview).addUseCase(imageCapture!!)

            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()

                // वीडियो बिल्डर का सेटअप
                val videoCaptureBuilder = VideoCapture.withOutput(recorder)
                
                // फ़िक्स: यहाँ जो .setTargetTargetRotation था, उसे सही करके .setTargetRotation किया गया है
                videoCaptureBuilder.setTargetRotation(Surface.ROTATION_90)

                // Camera2Interop: Video mode के लिए सख्त FPS और Full FOV सेटिंग इंजेक्ट करना
                val videoExtender = Camera2Interop.Extender(videoCaptureBuilder)
                videoExtender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recordingFps)

                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val targetId = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "1" else "0"
                val characteristics = cameraManager.getCameraCharacteristics(targetId)
                val activeArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                if (activeArraySize != null) {
                    videoExtender.setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, activeArraySize)
                }

                // फ़िक्स: अब यह सही वेरिएबल से .build() होकर वीडियोकैप्चर ऑब्जेक्ट असाइन करेगा
                videoCapture = videoCaptureBuilder.build()
                useCaseGroupBuilder.addUseCase(preview).addUseCase(videoCapture!!)
            }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())
                if (isFlashOn) {
                    camera?.cameraControl?.enableTorch(true)
                }
            } catch (exc: Exception) {
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaActionSound.release()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val PREFS_APP_COUNTERS = "CameraAppCounters"
        private const val KEY_VIDEO_COUNTER = "video_counter"
        private const val KEY_PHOTO_COUNTER = "photo_counter"

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).toTypedArray()
    }
}
