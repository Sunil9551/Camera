package com.camera.app

import android.app.AlertDialog
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import com.camera.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        var is30Fps = prefs.getBoolean(KEY_IS_30FPS, true)
        var isHighResPhoto = prefs.getBoolean(KEY_HIGH_RES_PHOTO, false)

        binding.btnFps.text = if (is30Fps) "30 FPS" else "24 FPS"
        binding.btnHighRes.text = if (isHighResPhoto) "ON" else "OFF"

        binding.btnFps.setOnClickListener {
            is30Fps = !is30Fps
            binding.btnFps.text = if (is30Fps) "30 FPS" else "24 FPS"
            prefs.edit().putBoolean(KEY_IS_30FPS, is30Fps).apply()
        }

        binding.btnHighRes.setOnClickListener {
            isHighResPhoto = !isHighResPhoto
            binding.btnHighRes.text = if (isHighResPhoto) "ON" else "OFF"
            prefs.edit().putBoolean(KEY_HIGH_RES_PHOTO, isHighResPhoto).apply()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCameraInfo.setOnClickListener {
            showCameraInfo()
        }
    }

    private fun showCameraInfo() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val sb = StringBuilder()

        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)

                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                val facingText = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    else -> "OTHER"
                }

                val activeArray =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                val hwLevel =
                    characteristics.get(
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
                    )

                val map: StreamConfigurationMap? =
                    characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )

                sb.append("Camera $id ($facingText)\n")
                sb.append("Sensor active array: $activeArray\n")
                sb.append("Hardware level: $hwLevel\n")

                val jpegSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.JPEG)
                sb.append("\nJPEG sizes (top 8, largest first):\n")
                jpegSizes
                    ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
                    ?.take(8)
                    ?.forEach {
                        val mp = (it.width.toLong() * it.height.toLong()) / 1_000_000.0
                        sb.append("${it.width}x${it.height}  (~%.1fMP)\n".format(mp))
                    }

                val privSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.PRIVATE)
                sb.append("\nPreview/Video sizes (top 8, largest first):\n")
                privSizes
                    ?.sortedByDescending { it.width.toLong() * it.height.toLong() }
                    ?.take(8)
                    ?.forEach {
                        sb.append("${it.width}x${it.height}\n")
                    }

                sb.append("\n----------------------\n\n")
            }
        } catch (e: Exception) {
            sb.append("Error: ${e.message}")
        }

        AlertDialog.Builder(this)
            .setTitle("Camera Info")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        const val PREFS_NAME = "CameraAppSettings"
        const val KEY_IS_30FPS = "is_30fps"
        const val KEY_HIGH_RES_PHOTO = "is_high_res_photo"
    }
}
