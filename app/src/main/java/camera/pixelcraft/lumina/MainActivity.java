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

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.params.MeteringRectangle;
import android.view.ViewGroup;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioAttributes;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.net.Uri;
import android.util.Size;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 101;
    private static final String PHOTO_FOLDER = "Images";
    private static final String VIDEO_FOLDER = "Video clips";

    private TextureView viewFinder;
    private TextView tvTimer;
    private View redBlinkingDot;
    private ImageView imgPauseIndicator;
    private ImageButton btnRecord, btnStop, btnSwitchCam, btnFlash, btnSettings;
    private TextView btnPhotoMode, btnVideoMode;
    private Button btnZoomIn, btnZoomOut;

    // Camera2 variables
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private String cameraId;
    private CameraCharacteristics cameraCharacteristics;
    private Rect activeArray;
    private CameraCapabilities.RecordingConfig config;
    private List<Size> supportedSizes = new ArrayList<>();
    private Surface previewSurface;
    private CbrVideoRecorder mediaRecorder;
    private SoundPool soundPool;
    private int soundCameraClick;
    private int soundStartVideo;
    private int soundStopVideo;
    private ImageReader photoImageReader;
    private Size photoSize;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private SettingsManager settingsManager;
    private OrientationEventListener orientationEventListener;

    private volatile boolean resumed = false;
    private volatile boolean cameraOpening = false;
    private boolean isRecording = false;
    private long recordingStartElapsedMs = 0L;
    private long pauseStartedElapsedMs = 0L;

    // Start sound: recording begins only after it has finished playing, so it is never recorded.
    // Time from the tap until recording may begin. Raise it if the start sound is still audible.
    private static final long START_SOUND_GUARD_MS = 1000L;
    private long startSoundPlayedAtMs = 0L;
    private boolean startingRecording = false;
    private final Runnable startRecorderRunnable = this::startRecorderNow;

    // Touch to focus
    private static final long FOCUS_LOCK_MS = 1500L;
    private static final long FOCUS_RING_MS = 1200L;
    @Nullable
    private MeteringRectangle[] meteringRegion;
    @Nullable
    private GradientDrawable focusRing;
    private final Runnable releaseFocusRunnable = this::releaseFocusLock;
    private final Runnable hideFocusRingRunnable = this::hideFocusRing;
    private boolean isPaused = false;
    private boolean isFlashOn = false;
    private boolean photoMode = true;
    private boolean pendingVideoMode = false;
    private boolean flashSupported = false;
    private int deviceOrientationDegrees = 0;
    private int sensorOrientation = 0;

    /** Lowered automatically if this phone fails to record at a size. */
    private int maxAllowedWidth = Integer.MAX_VALUE;

    // Where the current video is being written
    private Uri currentVideoUri;               // Android 10+ (MediaStore)
    private File currentVideoFile;             // Android 9 and older
    private ParcelFileDescriptor currentVideoPfd;
    private Uri currentPhotoUri;
    private File currentPhotoFile;

    private int secondsElapsed = 0;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            secondsElapsed = (int)((android.os.SystemClock.elapsedRealtime() - recordingStartElapsedMs) / 1000L);
            int hrs = secondsElapsed / 3600;
            int mins = (secondsElapsed % 3600) / 60;
            int secs = secondsElapsed % 60;
            tvTimer.setText(String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs));
            timerHandler.postDelayed(this, 1000);
        }
    };

    private final Handler zoomHandler = new Handler(Looper.getMainLooper());
    private float zoomRatio = 1.0f;
    private float maxZoomRatio = 1.0f;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCameraSafely();
        }

        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    // ---------------------------------------------------------------------------------
    // Activity lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        setupWindowInsets();

        settingsManager = new SettingsManager(this);
        loadSounds();

        initViews();
        setupOrientationListener();

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraIfPossible();
    }

    @Override
    protected void onPause() {
        resumed = false;
        timerHandler.removeCallbacks(releaseFocusRunnable);
        hideFocusRing();
        if (startingRecording) {
            // Left the app while waiting for the start sound to finish: nothing recorded yet.
            releaseMediaRecorder();
            finishOutput(false);
        }
        if (isRecording) {
            finishRecording(false); // save what we have instead of losing the video
        }
        closeCamera();
        stopBackgroundThread();
        zoomHandler.removeCallbacksAndMessages(null);
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        timerHandler.removeCallbacks(timerRunnable);
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        super.onDestroy();
    }

    /** Own sound effects (res/raw): camera_click, start_video, stop_video. */
    private void loadSounds() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build();

        soundCameraClick = soundPool.load(this, R.raw.camera_click, 1);
        soundStartVideo = soundPool.load(this, R.raw.start_video, 1);
        soundStopVideo = soundPool.load(this, R.raw.stop_video, 1);
    }

    private void playSound(int soundId) {
        if (soundPool != null && soundId != 0) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;

        if (!allPermissionsGranted()) {
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingVideoMode && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingVideoMode = false;
            photoMode = false;
            closeCaptureSession();
            updateModeUi();
            if (cameraDevice != null) {
                startPreview();
                return;
            }
        }
        startCameraIfPossible();
    }

    private void startCameraIfPossible() {
        if (!allPermissionsGranted()) return;
        resumed = true;
        startBackgroundThread();
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
        if (viewFinder.isAvailable()) {
            openCameraSafely();
        } else {
            viewFinder.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    // ---------------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------------

    /** Must have: camera (and storage on Android 9 and older). Microphone is optional. */
    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.CAMERA};
    }

    private String[] permissionsToRequest() {
        List<String> list = new ArrayList<>(Arrays.asList(requiredPermissions()));
        list.add(Manifest.permission.RECORD_AUDIO);
        return list.toArray(new String[0]);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean allPermissionsGranted() {
        for (String permission : requiredPermissions()) {
            if (!hasPermission(permission)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------

    /**
     * Gives the screen the same look on every phone: the layout is padded by the REAL status bar,
     * notch/cutout and navigation bar sizes of the device instead of fixed dp margins.
     */
    private void setupWindowInsets() {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        View root = findViewById(R.id.rootLayout);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void initViews() {
        viewFinder = findViewById(R.id.viewFinder);
        tvTimer = findViewById(R.id.tvTimer);
        redBlinkingDot = findViewById(R.id.redBlinkingDot);
        imgPauseIndicator = findViewById(R.id.imgPauseIndicator);
        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnFlash = findViewById(R.id.btnFlash);
        btnSettings = findViewById(R.id.btnSettings);
        btnPhotoMode = findViewById(R.id.btnPhotoMode);
        btnVideoMode = findViewById(R.id.btnVideoMode);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);

        btnRecord.setOnClickListener(v -> {
            if (photoMode) takePhoto(); else toggleRecording();
        });
        btnStop.setOnClickListener(v -> stopRecording());
        btnPhotoMode.setOnClickListener(v -> switchToPhotoMode());
        btnVideoMode.setOnClickListener(v -> switchToVideoMode());
        btnSwitchCam.setOnClickListener(v -> switchCamera());
        btnFlash.setOnClickListener(v -> toggleFlash());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        setupTouchToFocus();
        setupZoomControls();
        updateModeUi();
    }

    private void updateModeUi() {
        btnPhotoMode.setSelected(photoMode);
        btnVideoMode.setSelected(!photoMode);
        btnStop.setVisibility(photoMode ? View.GONE : (isRecording ? View.VISIBLE : View.GONE));
        // Timer shows only while recording. INVISIBLE (not GONE) keeps the top bar from moving.
        tvTimer.setVisibility(photoMode || !isRecording ? View.INVISIBLE : View.VISIBLE);
        redBlinkingDot.setVisibility(photoMode || !isRecording ? View.INVISIBLE : View.VISIBLE);
        imgPauseIndicator.setVisibility(photoMode || !isPaused ? View.GONE : View.VISIBLE);
        if (photoMode) {
            tvTimer.setText("PHOTO");
        } else if (!isRecording) {
            tvTimer.setText("00:00:00");
        }
    }

    private void switchToPhotoMode() {
        if (photoMode || isRecording) return;
        photoMode = true;
        closeCaptureSession();
        updateModeUi();
        startPreview();
    }

    private void switchToVideoMode() {
        if (!photoMode || isRecording) return;
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingVideoMode = true;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }
        photoMode = false;
        closeCaptureSession();
        updateModeUi();
        startPreview();
    }

    /** Makes the viewfinder the same shape as the selected capture mode. */
    private void updateViewfinderRatio() {
        if (config == null) return;
        boolean portraitSensor = sensorOrientation == 90 || sensorOrientation == 270;
        int w = config.previewSize.getWidth();
        int h = config.previewSize.getHeight();
        final String ratio = portraitSensor ? h + ":" + w : w + ":" + h;

        ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) viewFinder.getLayoutParams();
        if (!ratio.equals(params.dimensionRatio)) {
            params.dimensionRatio = ratio;
            viewFinder.setLayoutParams(params);
        }
    }

    /** Hides buttons this camera cannot use (flash on a front camera, switch on a one-camera phone). */
    private void updateControlsForCamera() {
        btnFlash.setVisibility(flashSupported ? View.VISIBLE : View.INVISIBLE);
        btnSwitchCam.setVisibility(findOtherCameraId() != null ? View.VISIBLE : View.INVISIBLE);
    }

    // ---------------------------------------------------------------------------------
    // Opening the camera
    // ---------------------------------------------------------------------------------

    private void openCameraSafely() {
        if (!allPermissionsGranted() || cameraDevice != null || cameraOpening
                || backgroundHandler == null || !resumed) {
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraId == null) {
                cameraId = CameraCapabilities.findDefaultCameraId(manager);
            }
            if (cameraId == null) {
                Toast.makeText(this, "No camera found on this phone", Toast.LENGTH_LONG).show();
                return;
            }

            cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 90;
            activeArray = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            flashSupported = CameraCapabilities.hasFlash(cameraCharacteristics);
            maxZoomRatio = CameraCapabilities.maxZoom(cameraCharacteristics);
            zoomRatio = 1.0f;
            isFlashOn = false;

            // Ask the phone what it can do, then pick the best match for the user's choice
            supportedSizes = CameraCapabilities.getSupportedVideoSizes(cameraCharacteristics);
            config = CameraCapabilities.createConfig(cameraCharacteristics, supportedSizes,
                    settingsManager.getResolutionWidth(), maxAllowedWidth);
            if (config == null) {
                Toast.makeText(this, "This camera cannot record video", Toast.LENGTH_LONG).show();
                return;
            }
            updateViewfinderRatio();
            updateControlsForCamera();

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                cameraOpening = true;
                manager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            cameraOpening = false;
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraOpening = false;
            if (!resumed) { // the user left while the camera was opening
                camera.close();
                return;
            }
            cameraDevice = camera;
            runOnUiThread(() -> startPreview());
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraOpening = false;
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
            runOnUiThread(() -> {
                if (isRecording) finishRecording(false);
            });
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraOpening = false;
            camera.close();
            if (cameraDevice == camera) cameraDevice = null;
            runOnUiThread(() -> {
                if (isRecording) finishRecording(false);
                Toast.makeText(MainActivity.this, "Camera error, please try again", Toast.LENGTH_LONG).show();
            });
        }
    };

    // ---------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------

    @Nullable
    private Surface createPreviewSurface() {
        SurfaceTexture texture = viewFinder.getSurfaceTexture();
        if (texture == null || config == null) return null;
        texture.setDefaultBufferSize(config.previewSize.getWidth(), config.previewSize.getHeight());
        if (previewSurface != null) {
            previewSurface.release();
        }
        previewSurface = new Surface(texture);
        return previewSurface;
    }

    private void startPreview() {
        if (cameraDevice == null || config == null || !viewFinder.isAvailable()) return;
        try {
            closeCaptureSession();
            Surface surface = createPreviewSurface();
            if (surface == null) return;

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            meteringRegion = null;
            captureRequestBuilder.addTarget(surface);
            applyCommonSettings();

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            if (photoMode) {
                ensurePhotoReader();
                if (photoImageReader != null) surfaces.add(photoImageReader.getSurface());
            }

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    runOnUiThread(() -> onPreviewFailed());
                }
            }, backgroundHandler);

        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
        }
    }

    /** The phone refused the preview at this size: step down one size and try again. */
    private void onPreviewFailed() {
        if (!lowerResolution()) {
            Toast.makeText(this, "Preview is not supported on this camera", Toast.LENGTH_LONG).show();
            return;
        }
        startPreview();
    }

    private void updatePreview() {
        if (cameraDevice == null || captureSession == null || captureRequestBuilder == null) return;
        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
        }
    }

    /** Settings shared by the preview and the recording request. */
    private void applyCommonSettings() {
        if (captureRequestBuilder == null || config == null) return;

        if (config.fpsRange != null) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange);
        }
        Integer antibanding = CameraCapabilities.pickAntibandingMode(cameraCharacteristics);
        if (antibanding != null) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding);
        }
        if (flashSupported) {
            captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    isFlashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
        applyZoomRegion();
    }

    // ---------------------------------------------------------------------------------
    // Photo capture
    // ---------------------------------------------------------------------------------

    private void ensurePhotoReader() {
        if (photoImageReader != null || cameraCharacteristics == null) return;
        try {
            android.hardware.camera2.params.StreamConfigurationMap map =
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = null;
            if (map != null) {
                Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                if (sizes != null) {
                    for (Size s : sizes) {
                        if (s.getWidth() > 4096 || s.getHeight() > 4096) continue;
                        boolean fourThree = s.getWidth() * 3 == s.getHeight() * 4;
                        if (best == null || (fourThree && !(best.getWidth() * 3 == best.getHeight() * 4))
                                || (fourThree == (best.getWidth() * 3 == best.getHeight() * 4)
                                && (long)s.getWidth() * s.getHeight() > (long)best.getWidth() * best.getHeight())) {
                            best = s;
                        }
                    }
                }
            }
            if (best == null) best = config != null ? config.videoSize : new Size(640, 480);
            photoSize = best;
            photoImageReader = ImageReader.newInstance(best.getWidth(), best.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            photoImageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    savePhoto(bytes);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not save photo", Toast.LENGTH_SHORT).show());
                } finally {
                    if (image != null) image.close();
                }
            }, backgroundHandler);
        } catch (RuntimeException e) {
            photoImageReader = null;
        }
    }

    private void takePhoto() {
        if (photoMode && cameraDevice != null && captureSession != null && photoImageReader != null) {
            try {
                CaptureRequest.Builder request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                request.addTarget(photoImageReader.getSurface());
                int[] afModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                if (afModes != null) {
                    for (int mode : afModes) {
                        if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                            request.set(CaptureRequest.CONTROL_AF_MODE, mode);
                            break;
                        }
                    }
                }
                request.set(CaptureRequest.JPEG_ORIENTATION, getOrientationHint());
                if (flashSupported) {
                    request.set(CaptureRequest.FLASH_MODE, isFlashOn
                            ? CaptureRequest.FLASH_MODE_SINGLE : CaptureRequest.FLASH_MODE_OFF);
                }
                applyZoomTo(request);
                applyMeteringRegions(request);
                captureSession.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull android.hardware.camera2.TotalCaptureResult result) {
                        playSound(soundCameraClick);
                    }
                }, backgroundHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Toast.makeText(this, "Could not take photo", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void applyZoomTo(CaptureRequest.Builder request) {
        if (activeArray == null) return;
        int cropW = (int) (activeArray.width() / zoomRatio);
        int cropH = (int) (activeArray.height() / zoomRatio);
        int left = activeArray.left + (activeArray.width() - cropW) / 2;
        int top = activeArray.top + (activeArray.height() - cropH) / 2;
        request.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + cropW, top + cropH));
    }

    private void savePhoto(byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + PHOTO_FOLDER);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = insertWithSequentialName(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values, PHOTO_FOLDER, "Image_%04d.jpg");
            currentPhotoUri = uri;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Could not open photo");
                out.write(bytes);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            currentPhotoUri = null;
        } else {
            String name = nextPhotoName();
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), PHOTO_FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create photo folder");
            File file = new File(dir, name);
            currentPhotoFile = file;
            try (OutputStream out = new java.io.FileOutputStream(file)) { out.write(bytes); }
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            currentPhotoFile = null;
        }
        runOnUiThread(() -> Toast.makeText(this, "Photo saved", Toast.LENGTH_SHORT).show());
    }

    /**
     * Creates the MediaStore entry with the next free sequential name
     * (Video_clip_0000.mp4, Video_clip_0001.mp4 ...).
     *
     * Android hides media that other installs of the app made (for example from before the
     * package name changed), so our list of used names can be incomplete. When a hidden file
     * already has the name, Android quietly renames our file to "Name (1).ext". We notice
     * that, throw our entry away and simply try the next number.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private Uri insertWithSequentialName(Uri collection, ContentValues values, String folder, String pattern)
            throws IOException {
        Set<String> used = usedNames(collection, folder);
        int index = 0;

        for (int attempt = 0; attempt < 2000; attempt++) {
            while (used.contains(String.format(Locale.US, pattern, index))) {
                index++;
            }
            String name = String.format(Locale.US, pattern, index);

            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) throw new IOException("Could not create the media entry");

            String actual = displayNameOf(uri);
            if (actual == null || actual.equals(name)) {
                return uri; // got exactly the name we asked for (or cannot tell: keep it)
            }

            getContentResolver().delete(uri, null, null);
            used.add(name);
        }
        throw new IOException("No free file name found");
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private Set<String> usedNames(Uri collection, String folder) {
        Set<String> used = new HashSet<>();
        String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] args = {Environment.DIRECTORY_DCIM + "/" + folder + "/"};
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, args, null)) {
            while (cursor != null && cursor.moveToNext()) {
                used.add(cursor.getString(0));
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
        return used;
    }

    /** The name Android really gave the new entry (it is still "pending", so ask for those too). */
    @Nullable
    @SuppressWarnings("deprecation")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private String displayNameOf(Uri uri) {
        Uri target = uri;
        Bundle extras = new Bundle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        } else {
            target = MediaStore.setIncludePending(uri);
        }
        try (Cursor cursor = getContentResolver().query(
                target, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, extras, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
        return null;
    }

    /** Image_0000.jpg, Image_0001.jpg ... sequential image filename. */
    private String nextPhotoName() {
        Set<String> used = new HashSet<>();
        File photoDir = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] args = {Environment.DIRECTORY_DCIM + "/" + PHOTO_FOLDER + "/"};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    used.add(cursor.getString(0));
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        } else {
            photoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), PHOTO_FOLDER);
        }

        int count = 0;
        String name;
        do {
            name = String.format(Locale.US, "Image_%04d.jpg", count++);
        } while (used.contains(name) || (photoDir != null && new File(photoDir, name).exists()));
    return name;
    }

    // ---------------------------------------------------------------------------------
    // Recording
    // ---------------------------------------------------------------------------------

    private void toggleRecording() {
        if (startingRecording) return;
        if (!isRecording) {
            startRecording();
        } else if (!isPaused) {
            pauseRecording();
        } else {
            resumeRecording();
        }
    }

    private void startRecording() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingVideoMode = true;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }
        if (cameraDevice == null || config == null || !viewFinder.isAvailable()) return;

        // Start sound plays now, while the camera gets ready. Recording itself starts after it.
        playSound(soundStartVideo);
        startSoundPlayedAtMs = android.os.SystemClock.elapsedRealtime();

        try {
            closeCaptureSession();
            setUpMediaRecorder();

            Surface surface = createPreviewSurface();
            if (surface == null) return;
            Surface recorderSurface = mediaRecorder.getSurface();

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            meteringRegion = null;
            captureRequestBuilder.addTarget(surface);
            captureRequestBuilder.addTarget(recorderSurface);
            applyCommonSettings();

            cameraDevice.createCaptureSession(Arrays.asList(surface, recorderSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    updatePreview();
                    runOnUiThread(() -> beginRecording());
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    runOnUiThread(() -> onRecordingSetupFailed());
                }
            }, backgroundHandler);

        } catch (StorageException e) {
            e.printStackTrace();
            onRecordingStorageFailed();
        } catch (Exception e) {
            e.printStackTrace();
            onRecordingSetupFailed();
        }
    }

    private void beginRecording() {
        if (mediaRecorder == null || !resumed) return;

        // The start sound began when the button was tapped. Recording starts only once it has
        // finished, otherwise the microphone would record it. The camera usually needs longer
        // than that to get ready anyway, so this adds little or no extra waiting.
        long wait = START_SOUND_GUARD_MS
                - (android.os.SystemClock.elapsedRealtime() - startSoundPlayedAtMs);
        if (wait > 0) {
            startingRecording = true;
            timerHandler.postDelayed(startRecorderRunnable, wait);
        } else {
            startRecorderNow();
        }
    }

    private void startRecorderNow() {
        startingRecording = false;

        if (mediaRecorder == null || captureSession == null || !resumed) {
            // The camera was closed or switched while we waited: drop this recording quietly.
            releaseMediaRecorder();
            finishOutput(false);
            return;
        }

        try {
            mediaRecorder.start();
        } catch (RuntimeException e) {
            e.printStackTrace();
            onRecordingSetupFailed();
            return;
        }

        isRecording = true;
        isPaused = false;
        btnStop.setVisibility(View.VISIBLE);
        imgPauseIndicator.setVisibility(View.GONE);
        startBlinkingDot();

        secondsElapsed = 0;
        recordingStartElapsedMs = android.os.SystemClock.elapsedRealtime();
        tvTimer.setText("00:00:00");
        tvTimer.setVisibility(View.VISIBLE);
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    /** The video file could not be created (storage full or blocked) - a smaller size will not help. */
    private void onRecordingStorageFailed() {
        releaseMediaRecorder();
        finishOutput(false);
        isRecording = false;
        Toast.makeText(this, "Could not save the video. Please check free storage.", Toast.LENGTH_LONG).show();
        startPreview();
    }

    /**
     * This phone could not record at the current size. Instead of failing silently the app
     * steps down to the next smaller size, remembers it, and tries again by itself.
     */
    private void onRecordingSetupFailed() {
        releaseMediaRecorder();
        finishOutput(false);
        isRecording = false;

        if (lowerResolution()) {
            startRecording();
        } else {
            Toast.makeText(this, "Recording is not supported on this phone", Toast.LENGTH_LONG).show();
            startPreview();
        }
    }

    /** Switches to the next smaller supported size. Returns false if there is none. */
    private boolean lowerResolution() {
        if (config == null || cameraCharacteristics == null) return false;

        int failedWidth = config.videoSize.getWidth();
        CameraCapabilities.RecordingConfig lower = CameraCapabilities.createConfig(
                cameraCharacteristics, supportedSizes,
                settingsManager.getResolutionWidth(), failedWidth - 1);
        if (lower == null) return false;

        maxAllowedWidth = failedWidth - 1;
        config = lower;
        settingsManager.setResolutionWidth(lower.videoSize.getWidth());
        updateViewfinderRatio();
        Toast.makeText(this, String.format(Locale.US, "Resolution lowered to %d×%d for this phone",
                lower.videoSize.getWidth(), lower.videoSize.getHeight()), Toast.LENGTH_LONG).show();
        return true;
    }

    private void pauseRecording() {
        if (mediaRecorder == null) return;
        try {
            mediaRecorder.pause();
        } catch (RuntimeException e) {
            e.printStackTrace();
            return;
        }
        isPaused = true;
        pauseStartedElapsedMs = android.os.SystemClock.elapsedRealtime();
        stopBlinkingDot();
        imgPauseIndicator.setVisibility(View.VISIBLE);
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void resumeRecording() {
        if (mediaRecorder == null) return;
        try {
            mediaRecorder.resume();
        } catch (RuntimeException e) {
            e.printStackTrace();
            return;
        }
        isPaused = false;
        // The time spent paused is not recording time: move the start forward by that much,
        // so 5 s + pause + resume carries on from 5 s, not from 5 s + pause length.
        recordingStartElapsedMs += android.os.SystemClock.elapsedRealtime() - pauseStartedElapsedMs;
        imgPauseIndicator.setVisibility(View.GONE);
        startBlinkingDot();
        timerHandler.postDelayed(timerRunnable, 1000);
    }

    private void stopRecording() {
        finishRecording(true);
    }

    private void finishRecording(boolean restartPreview) {
        boolean saved = false;
        if (isRecording && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                saved = true;
            } catch (RuntimeException e) {
                // stop() fails when almost nothing was recorded - that file is useless
            }
            playSound(soundStopVideo);
        }
        closeCaptureSession();
        releaseMediaRecorder();
        finishOutput(saved);
        if (saved) {
            Toast.makeText(this, "Video saved", Toast.LENGTH_SHORT).show();
        }

        isRecording = false;
        isPaused = false;
        btnStop.setVisibility(View.GONE);

        stopBlinkingDot();
        imgPauseIndicator.setVisibility(View.GONE);
        timerHandler.removeCallbacks(timerRunnable);
        tvTimer.setText("00:00:00");

        updateModeUi();
        if (restartPreview) {
            startPreview();
        }
    }

    /** Problem with the output file itself (not with the camera or encoder). */
    private static class StorageException extends IOException {
        StorageException(String message) {
            super(message);
        }
    }

    private void setUpMediaRecorder() throws IOException {
        releaseMediaRecorder();

        boolean withAudio = hasPermission(Manifest.permission.RECORD_AUDIO);

        // Constant-bitrate recorder: H.264 + AAC, audio + video never above the size's cap.
        mediaRecorder = new CbrVideoRecorder(
                config.videoSize.getWidth(),
                config.videoSize.getHeight(),
                config.frameRate,
                config.bitrate,
                getOrientationHint(),
                withAudio);

        try {
            openOutput();
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            throw new StorageException(String.valueOf(e.getMessage()));
        }

        mediaRecorder.prepare();
    }

    private void releaseMediaRecorder() {
        timerHandler.removeCallbacks(startRecorderRunnable);
        startingRecording = false;
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder = null;
        }
    }

    private int getOrientationHint() {
        if (cameraCharacteristics == null) return 0;

        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        boolean isFrontFacing = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);

        if (isFrontFacing) {
            return (sensorOrientation - deviceOrientationDegrees + 360) % 360;
        } else {
            return (sensorOrientation + deviceOrientationDegrees) % 360;
        }
    }

    // ---------------------------------------------------------------------------------
    // Saving the file (MediaStore on Android 10+, plain file on Android 9 and older)
    // ---------------------------------------------------------------------------------

    /** Video_clip_0000.mp4, Video_clip_0001.mp4 ... sequetial video filename. */
    private String nextVideoName() {
        Set<String> used = new HashSet<>();
        File videoDir = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
            String[] args = {Environment.DIRECTORY_DCIM + "/" + VIDEO_FOLDER + "/"};
            try (Cursor cursor = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    used.add(cursor.getString(0));
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        } else {
            videoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), VIDEO_FOLDER);
        }

        int count = 0;
        String name;
        do {
            name = String.format(Locale.US, "Video_clip_%04d.mp4", count++);
    } while (used.contains(name) || (videoDir != null && new File(videoDir, name).exists()));
    return name;
}

    private void openOutput() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + VIDEO_FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            currentVideoUri = insertWithSequentialName(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values, VIDEO_FOLDER, "Video_clip_%04d.mp4");

            currentVideoPfd = getContentResolver().openFileDescriptor(currentVideoUri, "rw");
            if (currentVideoPfd == null) throw new IOException("Could not open the video file");
            mediaRecorder.setOutputFile(currentVideoPfd.getFileDescriptor());
        } else {
            String name = nextVideoName();
            File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            File videoDir = new File(dcimDir, VIDEO_FOLDER);
            if (!videoDir.exists() && !videoDir.mkdirs()) throw new IOException("Could not create the folder");

            currentVideoFile = new File(videoDir, name);
            mediaRecorder.setOutputFile(currentVideoFile.getAbsolutePath());
        }
    }

    /** Closes the output. keep = true publishes the video, false throws the empty file away. */
    private void finishOutput(boolean keep) {
        if (currentVideoPfd != null) {
            try {
                currentVideoPfd.close();
            } catch (IOException ignored) {
            }
            currentVideoPfd = null;
        }

        if (currentVideoUri != null) {
            if (keep) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(currentVideoUri, values, null, null);
            } else {
                getContentResolver().delete(currentVideoUri, null, null);
            }
            currentVideoUri = null;
        }

        if (currentVideoFile != null) {
            if (keep) {
                MediaScannerConnection.scanFile(this, new String[]{currentVideoFile.getAbsolutePath()}, null, null);
            } else {
                //noinspection ResultOfMethodCallIgnored
                currentVideoFile.delete();
            }
            currentVideoFile = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Device orientation
    // ---------------------------------------------------------------------------------

    private void setupOrientationListener() {
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                if (orientation >= 315 || orientation < 45) {
                    deviceOrientationDegrees = 0;
                } else if (orientation >= 45 && orientation < 135) {
                    deviceOrientationDegrees = 90;
                } else if (orientation >= 135 && orientation < 225) {
                    deviceOrientationDegrees = 180;
                } else if (orientation >= 225 && orientation < 315) {
                    deviceOrientationDegrees = 270;
                }
            }
        };
    }

    // ---------------------------------------------------------------------------------
    // Camera switch, flash, close
    // ---------------------------------------------------------------------------------

    /** The camera facing the other way (back <-> front), or null if this phone has only one. */
    @Nullable
    private String findOtherCameraId() {
        if (cameraId == null || cameraCharacteristics == null) return null;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        int wanted = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                ? CameraCharacteristics.LENS_FACING_BACK
                : CameraCharacteristics.LENS_FACING_FRONT;

        String id = CameraCapabilities.findCameraId(manager, wanted);
        return (id != null && !id.equals(cameraId)) ? id : null;
    }

    private void switchCamera() {
        if (isRecording) return;
        String otherId = findOtherCameraId();
        if (otherId == null) return;

        closeCamera();
        cameraId = otherId;
        openCameraSafely();
    }

    private void toggleFlash() {
        if (!flashSupported || cameraDevice == null || captureRequestBuilder == null) return;
        isFlashOn = !isFlashOn;
        captureRequestBuilder.set(CaptureRequest.FLASH_MODE,
                isFlashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        updatePreview();
    }

    private void closeCamera() {
        closeCaptureSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        releaseMediaRecorder();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (photoImageReader != null) {
            photoImageReader.close();
            photoImageReader = null;
        }
        cameraOpening = false;
    }

    // ---------------------------------------------------------------------------------
    // Touch to focus
    // ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchToFocus() {
        viewFinder.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && event.getEventTime() - event.getDownTime() < 500) {
                focusAt(event.getX(), event.getY());
                v.performClick();
            }
            return true;
        });
    }

    private int maxRegions(CameraCharacteristics.Key<Integer> key) {
        if (cameraCharacteristics == null) return 0;
        Integer value = cameraCharacteristics.get(key);
        return value != null ? value : 0;
    }

    private boolean canAutoFocus() {
        if (cameraCharacteristics == null) return false;
        int[] modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null) return false;
        for (int mode : modes) {
            if (mode != CaptureRequest.CONTROL_AF_MODE_OFF) return true;
        }
        return false;
    }

    /**
     * Tap on the picture: focus (and measure the light) on that spot.
     * Works in photo mode, in video mode and while recording.
     */
    private void focusAt(float viewX, float viewY) {
        if (cameraDevice == null || captureSession == null || captureRequestBuilder == null
                || cameraCharacteristics == null || activeArray == null
                || backgroundHandler == null || !resumed) {
            return;
        }
        int w = viewFinder.getWidth();
        int h = viewFinder.getHeight();
        if (w <= 0 || h <= 0) return;

        boolean canFocus = canAutoFocus()
                && maxRegions(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0;
        boolean canMeter = maxRegions(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0;
        if (!canFocus && !canMeter) return; // fixed-focus camera: nothing to do

        meteringRegion = new MeteringRectangle[]{buildMeteringRectangle(viewX / w, viewY / h)};
        showFocusRing(viewX, viewY);

        try {
            CaptureRequest.Builder builder = captureRequestBuilder;
            applyMeteringRegions(builder);

            // 1) stop any earlier focus run, 2) start a new one on the tapped spot,
            // 3) carry on with the normal picture, which now looks at that spot.
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(builder.build(), null, backgroundHandler);

            if (canFocus) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                captureSession.capture(builder.build(), null, backgroundHandler);
            }

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);

            if (canFocus) {
                // After the quick focus scan, hand over to continuous focus again.
                // It keeps watching the tapped area, so a moving subject stays sharp.
                timerHandler.removeCallbacks(releaseFocusRunnable);
                timerHandler.postDelayed(releaseFocusRunnable, FOCUS_LOCK_MS);
            }
        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
        }
    }

    private void releaseFocusLock() {
        if (captureSession == null || captureRequestBuilder == null || backgroundHandler == null) return;
        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(captureRequestBuilder.build(), null, backgroundHandler);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
        }
    }

    /** Puts the last tapped area (if any) into a request - used by preview, recording and photo. */
    private void applyMeteringRegions(CaptureRequest.Builder request) {
        if (meteringRegion == null) return;
        if (maxRegions(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) > 0) {
            request.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRegion);
        }
        if (maxRegions(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) > 0) {
            request.set(CaptureRequest.CONTROL_AE_REGIONS, meteringRegion);
        }
    }

    /** The part of the sensor the picture currently shows (same rule as the zoom code). */
    private Rect currentCropRegion() {
        int cropW = (int) (activeArray.width() / zoomRatio);
        int cropH = (int) (activeArray.height() / zoomRatio);
        int left = activeArray.left + (activeArray.width() - cropW) / 2;
        int top = activeArray.top + (activeArray.height() - cropH) / 2;
        return new Rect(left, top, left + cropW, top + cropH);
    }

    @SuppressWarnings("deprecation")
    private int displayRotationDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    /**
     * Turns a point on the screen (0..1 across and down the picture) into an area on the sensor.
     * The sensor is turned relative to the screen, the front camera is shown as a mirror
     * image, and zoom shows only the middle of the sensor - all of that is handled here.
     */
    private MeteringRectangle buildMeteringRectangle(float nx, float ny) {
        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        int display = displayRotationDegrees();

        float x = (front ? 1f - nx : nx) - 0.5f;
        float y = ny - 0.5f;

        int turn = front
                ? (sensorOrientation + display) % 360
                : (sensorOrientation - display + 360) % 360;

        float sx;
        float sy;
        switch (turn) {
            case 90:
                sx = y;
                sy = -x;
                break;
            case 180:
                sx = -x;
                sy = -y;
                break;
            case 270:
                sx = -y;
                sy = x;
                break;
            default:
                sx = x;
                sy = y;
                break;
        }

        Rect crop = currentCropRegion();
        int centerX = Math.round(crop.left + (sx + 0.5f) * crop.width());
        int centerY = Math.round(crop.top + (sy + 0.5f) * crop.height());
        int half = Math.max(1, Math.round(0.1f * Math.min(crop.width(), crop.height())));

        Rect area = new Rect(
                Math.max(activeArray.left, centerX - half),
                Math.max(activeArray.top, centerY - half),
                Math.min(activeArray.right - 1, centerX + half),
                Math.min(activeArray.bottom - 1, centerY + half));

        return new MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    /** A thin white ring that pops onto the tapped spot and fades away. */
    private void showFocusRing(float viewX, float viewY) {
        if (!(viewFinder.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) viewFinder.getParent();

        hideFocusRing();

        float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(72 * density);
        final int cx = Math.round(viewFinder.getLeft() + viewX);
        final int cy = Math.round(viewFinder.getTop() + viewY);

        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(Math.max(2, Math.round(2 * density)), Color.WHITE);
        int startHalf = Math.round(size / 2f * 1.6f);
        ring.setBounds(cx - startHalf, cy - startHalf, cx + startHalf, cy + startHalf);

        parent.getOverlay().add(ring);
        focusRing = ring;

        ValueAnimator pop = ValueAnimator.ofFloat(1.6f, 1f);
        pop.setDuration(180);
        pop.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            int half = Math.round(size / 2f * scale);
            ring.setBounds(cx - half, cy - half, cx + half, cy + half);
            parent.invalidate();
        });
        pop.start();

        timerHandler.removeCallbacks(hideFocusRingRunnable);
        timerHandler.postDelayed(hideFocusRingRunnable, FOCUS_RING_MS);
    }

    private void hideFocusRing() {
        timerHandler.removeCallbacks(hideFocusRingRunnable);
        if (focusRing != null && viewFinder != null && viewFinder.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) viewFinder.getParent();
            parent.getOverlay().remove(focusRing);
            parent.invalidate();
        }
        focusRing = null;
    }

    private void closeCaptureSession() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
    }

    private void startBackgroundThread() {
        if (backgroundThread != null) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // ---------------------------------------------------------------------------------
    // Recording indicator and zoom
    // ---------------------------------------------------------------------------------

    private void startBlinkingDot() {
        redBlinkingDot.setVisibility(View.VISIBLE);
        AlphaAnimation blink = new AlphaAnimation(1.0f, 0.0f);
        blink.setDuration(250);
        blink.setRepeatCount(Animation.INFINITE);
        blink.setRepeatMode(Animation.REVERSE);
        redBlinkingDot.startAnimation(blink);
    }

    private void stopBlinkingDot() {
        redBlinkingDot.clearAnimation();
        redBlinkingDot.setVisibility(View.INVISIBLE);
    }

    private void setupZoomControls() {
        Runnable zoomIn = new Runnable() {
            @Override
            public void run() {
                if (cameraDevice != null && zoomRatio < maxZoomRatio) {
                    zoomRatio = Math.min(zoomRatio + 0.05f, maxZoomRatio);
                    applyZoom();
                    zoomHandler.postDelayed(this, 50);
                }
            }
        };

        Runnable zoomOut = new Runnable() {
            @Override
            public void run() {
                if (cameraDevice != null && zoomRatio > 1.0f) {
                    zoomRatio = Math.max(zoomRatio - 0.05f, 1.0f);
                    applyZoom();
                    zoomHandler.postDelayed(this, 50);
                }
            }
        };

        btnZoomIn.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) zoomHandler.post(zoomIn);
            else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) zoomHandler.removeCallbacks(zoomIn);
            return true;
        });

        btnZoomOut.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) zoomHandler.post(zoomOut);
            else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) zoomHandler.removeCallbacks(zoomOut);
            return true;
        });
    }

    private void applyZoom() {
        if (captureRequestBuilder == null) return;
        applyZoomRegion();
        updatePreview();
    }

    /** Digital zoom by cropping the centre of the sensor. Also re-applied when recording starts. */
    private void applyZoomRegion() {
        if (captureRequestBuilder == null || activeArray == null) return;

        int cropW = (int) (activeArray.width() / zoomRatio);
        int cropH = (int) (activeArray.height() / zoomRatio);
        int left = activeArray.left + (activeArray.width() - cropW) / 2;
        int top = activeArray.top + (activeArray.height() - cropH) / 2;

        captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(left, top, left + cropW, top + cropH));
    }
}
