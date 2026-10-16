/*
 * Copyright 2026 Sunil Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://apache.org
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package camera.pixelcraft.lumina;

import android.content.Context;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    private Spinner spinnerResolution;
    private SettingsManager settingsManager;

    /** Only the sizes this phone can really record. */
    private List<Size> supportedSizes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);
        spinnerResolution = findViewById(R.id.spinnerResolution);
        Button btnSave = findViewById(R.id.btnSaveSettings);

        supportedSizes = loadSupportedSizes();
        setupSpinner();
        loadCurrentSettings();

        btnSave.setOnClickListener(v -> {
            saveSettings();
            finish();
        });
    }

    private List<Size> loadSupportedSizes() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = CameraCapabilities.findDefaultCameraId(manager);
            if (cameraId != null) {
                return CameraCapabilities.getSupportedVideoSizes(
                        manager.getCameraCharacteristics(cameraId));
            }
        } catch (CameraAccessException | RuntimeException e) {
            e.printStackTrace();
        }
        return new ArrayList<>(Collections.singletonList(new Size(640, 480)));
    }

    private void setupSpinner() {
        List<String> labels = new ArrayList<>();
        for (Size size : supportedSizes) {
            labels.add(String.format(Locale.US, "%d×%d", size.getWidth(), size.getHeight()));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                ((TextView) view).setTextColor(Color.WHITE);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerResolution.setAdapter(adapter);
    }

    private void loadCurrentSettings() {
        Size current = CameraCapabilities.chooseVideoSize(
                supportedSizes, settingsManager.getResolutionWidth(), Integer.MAX_VALUE);
        int position = current != null ? supportedSizes.indexOf(current) : 0;
        spinnerResolution.setSelection(Math.max(position, 0));
    }

    private void saveSettings() {
        int position = spinnerResolution.getSelectedItemPosition();
        if (position >= 0 && position < supportedSizes.size()) {
            settingsManager.setResolutionWidth(supportedSizes.get(position).getWidth());
        }
    }
    }
