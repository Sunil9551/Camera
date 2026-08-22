package com.camera.app

import android.content.Context
import android.os.Bundle
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
    }

    companion object {
        const val PREFS_NAME = "CameraAppSettings"
        const val KEY_IS_30FPS = "is_30fps"
        const val KEY_HIGH_RES_PHOTO = "is_high_res_photo"
    }
}
