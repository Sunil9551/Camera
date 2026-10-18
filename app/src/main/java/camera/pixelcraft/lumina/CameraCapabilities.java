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

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Asks the phone what its camera and video encoder can really do, so the app never
 * has to guess. Budget phones and flagships answer differently; the app just follows
 * the answers (frame rate, resolution, bitrate, flash, zoom, anti-flicker).
 */
public final class CameraCapabilities {

    private static final int TARGET_FPS = 30;

    // Biggest 4:3 option we ever offer.
    private static final int MAX_WIDTH = 2880;

    // Preview does not need more.
    private static final int MAX_PREVIEW_WIDTH = 1440;

    private static final float MAX_ZOOM = 4.0f;

    // Bitrate formula (video only):
    // width × height × fps × 0.13
    private static final float BITS_PER_PIXEL = 0.13f;

    /** Audio (AAC) bitrate used by CbrVideoRecorder. It is counted inside the caps below. */
    public static final int AUDIO_BITRATE = 128_000;

    // Hard caps for audio + video TOGETHER:
    //   up to 1600×1200 -> 8 Mbps   (2 minutes ≈ 118 MB)
    //   2880×2160       -> 24 Mbps
    // Sizes in between get a cap in between.
    private static final long CAP_LOW_PIXELS = 1600L * 1200L;
    private static final long CAP_HIGH_PIXELS = 2880L * 2160L;
    private static final long CAP_LOW_BITRATE = 8_000_000L;
    private static final long CAP_HIGH_BITRATE = 24_000_000L;

    // Stay ~1.5% under the cap so the MP4 container overhead can never push it over.
    private static final float CAP_SAFETY = 0.985f;

    private static final Size FALLBACK_SIZE = new Size(640, 480);

    private static List<MediaCodecInfo.VideoCapabilities> avcEncoders;

    private CameraCapabilities() {
    }

    /** Everything needed to start one recording, already checked against the device. */
    public static final class RecordingConfig {

        public final Size videoSize;
        public final Size previewSize;

        @Nullable
        public final Range<Integer> fpsRange;

        public final int frameRate;

        /** VIDEO bitrate in bits per second (the audio bitrate comes on top of it). */
        public final int bitrate;

        RecordingConfig(
                Size videoSize,
                Size previewSize,
                @Nullable Range<Integer> fpsRange,
                int frameRate,
                int bitrate
        ) {
            this.videoSize = videoSize;
            this.previewSize = previewSize;
            this.fpsRange = fpsRange;
            this.frameRate = frameRate;
            this.bitrate = bitrate;
        }
    }

    // ---------------------------------------------------------------------------------
    // Camera selection
    // ---------------------------------------------------------------------------------

    /** Back camera first, then front, then any other usable camera. */
    @Nullable
    public static String findDefaultCameraId(@NonNull CameraManager manager) {

        String id = findCameraId(
                manager,
                CameraCharacteristics.LENS_FACING_BACK
        );

        if (id == null) {
            id = findCameraId(
                    manager,
                    CameraCharacteristics.LENS_FACING_FRONT
            );
        }

        if (id == null) {
            id = findCameraId(manager, -1);
        }

        return id;
    }

    /**
     * First usable camera that faces the given direction.
     * Pass -1 for "any direction".
     *
     * Cameras that only give depth/IR data are skipped.
     */
    @Nullable
    public static String findCameraId(
            @NonNull CameraManager manager,
            int facing
    ) {

        try {

            for (String id : manager.getCameraIdList()) {

                try {

                    CameraCharacteristics c =
                            manager.getCameraCharacteristics(id);

                    if (!isUsable(c)) {
                        continue;
                    }

                    Integer lens =
                            c.get(CameraCharacteristics.LENS_FACING);

                    if (facing == -1
                            || (lens != null && lens == facing)) {
                        return id;
                    }

                } catch (
                        CameraAccessException
                                | IllegalArgumentException ignored
                ) {
                    // Some phones list cameras they refuse to describe.
                    // Just skip those.
                }
            }

        } catch (CameraAccessException ignored) {
        }

        return null;
    }

    private static boolean isUsable(
            @NonNull CameraCharacteristics c
    ) {

        if (c.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) == null) {
            return false;
        }

        int[] caps =
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);

        if (caps == null) {
            return true;
        }

        for (int cap : caps) {

            if (cap ==
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
                return true;
            }
        }

        return false;
    }

    // ---------------------------------------------------------------------------------
    // Frame rate
    // ---------------------------------------------------------------------------------

    /**
     * Picks the steadiest range the camera supports, up to 30 fps
     * (for example [30,30]).
     *
     * Returns null when the camera does not report ranges.
     */
    @Nullable
    public static Range<Integer> pickFpsRange(
            @NonNull CameraCharacteristics c
    ) {

        Range<Integer>[] ranges =
                c.get(
                        CameraCharacteristics
                                .CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                );

        if (ranges == null || ranges.length == 0) {
            return null;
        }

        Range<Integer> best = null;

        for (Range<Integer> r : ranges) {

            if (r.getUpper() > TARGET_FPS) {
                continue;
            }

            if (best == null
                    || r.getUpper() > best.getUpper()
                    || (
                    r.getUpper().intValue()
                            == best.getUpper().intValue()
                            && r.getLower() > best.getLower()
            )) {

                best = r;
            }
        }

        if (best == null) {

            // Camera only offers ranges above 30 fps.
            // Take the slowest one.
            for (Range<Integer> r : ranges) {

                if (best == null
                        || r.getUpper() < best.getUpper()) {

                    best = r;
                }
            }
        }

        return best;
    }

    private static int frameRateFor(
            @Nullable Range<Integer> range
    ) {
        return range != null
                ? range.getUpper()
                : TARGET_FPS;
    }

    // ---------------------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------------------

    /**
     * All video sizes that BOTH the camera and the phone's H.264 encoder
     * can handle at the chosen frame rate, smallest first.
     *
     * Only 4:3 sizes are listed when the camera has any.
     */
    @NonNull
    public static List<Size> getSupportedVideoSizes(
            @NonNull CameraCharacteristics c
    ) {

        StreamConfigurationMap map =
                c.get(
                        CameraCharacteristics
                                .SCALER_STREAM_CONFIGURATION_MAP
                );

        Size[] all =
                map != null
                        ? map.getOutputSizes(MediaRecorder.class)
                        : null;

        if (all == null || all.length == 0) {
            return new ArrayList<>(
                    Collections.singletonList(FALLBACK_SIZE)
            );
        }

        int fps =
                frameRateFor(pickFpsRange(c));

        long frameDurationNs =
                1_000_000_000L / Math.max(1, fps);

        List<Size> fourByThree =
                new ArrayList<>();

        List<Size> others =
                new ArrayList<>();

        for (Size s : all) {

            if (s == null) {
                continue;
            }

            if (s.getWidth() > MAX_WIDTH
                    || s.getWidth() < s.getHeight()) {
                continue;
            }

            if (!canCameraDeliver(
                    map,
                    s,
                    frameDurationNs
            )) {
                continue;
            }

            if (!isEncodable(s, fps)) {
                continue;
            }

            if (isFourByThree(s)) {
                fourByThree.add(s);
            } else {
                others.add(s);
            }
        }

        List<Size> result =
                !fourByThree.isEmpty()
                        ? fourByThree
                        : others;

        if (result.isEmpty()) {

            // Last resort: smallest size the camera offers.
            Size smallest = all[0];

            for (Size s : all) {

                if (area(s) < area(smallest)) {
                    smallest = s;
                }
            }

            result.add(smallest);
        }

        Collections.sort(
                result,
                new Comparator<Size>() {
                    @Override
                    public int compare(Size a, Size b) {
                        return Long.compare(
                                area(a),
                                area(b)
                        );
                    }
                }
        );

        return result;
    }

    /**
     * Picks the supported size whose width is closest to the wish,
     * but not wider than maxWidth.
     */
    @Nullable
    public static Size chooseVideoSize(
            @NonNull List<Size> supported,
            int preferredWidth,
            int maxWidth
    ) {

        Size best = null;

        for (Size s : supported) {

            if (s.getWidth() > maxWidth) {
                continue;
            }

            if (best == null
                    || Math.abs(
                    s.getWidth() - preferredWidth
            ) < Math.abs(
                    best.getWidth() - preferredWidth
            )) {

                best = s;
            }
        }

        return best;
    }

    /**
     * Preview uses the same shape as the video
     * so the picture is not stretched but stays small.
     */
    @NonNull
    public static Size choosePreviewSize(
            @NonNull CameraCharacteristics c,
            @NonNull Size videoSize
    ) {

        StreamConfigurationMap map =
                c.get(
                        CameraCharacteristics
                                .SCALER_STREAM_CONFIGURATION_MAP
                );

        Size[] sizes =
                map != null
                        ? map.getOutputSizes(SurfaceTexture.class)
                        : null;

        if (sizes == null) {
            return videoSize;
        }

        Size best = null;

        for (Size s : sizes) {

            boolean sameShape =
                    (long) s.getWidth()
                            * videoSize.getHeight()
                            ==
                            (long) s.getHeight()
                                    * videoSize.getWidth();

            if (!sameShape
                    || s.getWidth() > MAX_PREVIEW_WIDTH) {
                continue;
            }

            if (best == null
                    || s.getWidth() > best.getWidth()) {

                best = s;
            }
        }

        return best != null
                ? best
                : videoSize;
    }

    /**
     * Builds a ready-to-use recording setup,
     * or null if no size fits under maxWidth.
     */
    @Nullable
    public static RecordingConfig createConfig(
            @NonNull CameraCharacteristics c,
            @NonNull List<Size> supportedSizes,
            int preferredWidth,
            int maxWidth
    ) {

        Size video =
                chooseVideoSize(
                        supportedSizes,
                        preferredWidth,
                        maxWidth
                );

        if (video == null) {
            return null;
        }

        Range<Integer> fpsRange =
                pickFpsRange(c);

        int fps =
                frameRateFor(fpsRange);

        return new RecordingConfig(
                video,
                choosePreviewSize(c, video),
                fpsRange,
                fps,
                calculateBitrate(video, fps)
        );
    }

    private static boolean canCameraDeliver(
            StreamConfigurationMap map,
            Size size,
            long frameDurationNs
    ) {

        try {

            long minDuration =
                    map.getOutputMinFrameDuration(
                            MediaRecorder.class,
                            size
                    );

            return minDuration <= 0
                    || minDuration <= frameDurationNs * 1.05;

        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static boolean isFourByThree(
            Size s
    ) {

        return s.getWidth() * 3
                == s.getHeight() * 4;
    }

    private static long area(
            Size s
    ) {

        return (long) s.getWidth()
                * s.getHeight();
    }

    // ---------------------------------------------------------------------------------
    // Encoder (H.264)
    // ---------------------------------------------------------------------------------

    /**
     * Audio + video together never go above the cap for this picture size
     * (8 Mbps up to 1600×1200, 24 Mbps at 2880×2160).
     */
    private static long totalBitrateCap(@NonNull Size size) {

        long pixels = area(size);

        if (pixels <= CAP_LOW_PIXELS) {
            return CAP_LOW_BITRATE;
        }

        if (pixels >= CAP_HIGH_PIXELS) {
            return CAP_HIGH_BITRATE;
        }

        double t = (double) (pixels - CAP_LOW_PIXELS)
                / (double) (CAP_HIGH_PIXELS - CAP_LOW_PIXELS);

        return (long) (CAP_LOW_BITRATE
                + t * (CAP_HIGH_BITRATE - CAP_LOW_BITRATE));
    }

    /**
     * VIDEO bitrate for this size and fps.
     *
     * Bitrate scales with picture size and fps, is then limited so that
     * audio + video together stay under the cap, and finally squeezed
     * into what the encoder allows.
     *
     * Examples (30 fps):
     * 1600x1200 -> 7.75 Mbps video + 0.128 audio ≈ 7.88 Mbps total (2 min ≈ 118 MB)
     * 2880x2160 -> 23.5 Mbps video + 0.128 audio ≈ 23.6 Mbps total
     */
    public static int calculateBitrate(
            @NonNull Size size,
            int fps
    ) {

        long safeFps =
                Math.max(1, fps);

        long videoCap =
                (long) (totalBitrateCap(size) * CAP_SAFETY)
                        - AUDIO_BITRATE;

        long calculatedBitrate =
                (long) (
                        (double) size.getWidth()
                                * size.getHeight()
                                * safeFps
                                * BITS_PER_PIXEL
                );

        // Minimum bitrate = 500 kbps.
        calculatedBitrate =
                Math.max(
                        500_000L,
                        calculatedBitrate
                );

        // The cap always wins.
        calculatedBitrate =
                Math.min(
                        calculatedBitrate,
                        videoCap
                );

        int bitrate =
                (int) Math.min(
                        calculatedBitrate,
                        Integer.MAX_VALUE
                );

        for (
                MediaCodecInfo.VideoCapabilities vc
                : getAvcEncoders()
        ) {

            try {

                if (vc.isSizeSupported(
                        size.getWidth(),
                        size.getHeight()
                )) {

                    Range<Integer> bitrateRange =
                            vc.getBitrateRange();

                    if (bitrateRange != null) {

                        bitrate =
                                bitrateRange.clamp(bitrate);
                    }

                    break;
                }

            } catch (RuntimeException ignored) {
            }
        }

        // Our cap wins over whatever the encoder range said.
        return (int) Math.max(
                500_000L,
                Math.min(
                        bitrate,
                        videoCap
                )
        );
    }

    private static boolean isEncodable(
            Size s,
            int fps
    ) {

        List<MediaCodecInfo.VideoCapabilities> encoders =
                getAvcEncoders();

        if (encoders.isEmpty()) {
            // Cannot check - let runtime fallback handle it.
            return true;
        }

        for (
                MediaCodecInfo.VideoCapabilities vc
                : encoders
        ) {

            try {

                if (vc.isSizeSupported(
                        s.getWidth(),
                        s.getHeight()
                )
                        && vc.areSizeAndRateSupported(
                        s.getWidth(),
                        s.getHeight(),
                        fps
                )) {

                    return true;
                }

            } catch (RuntimeException ignored) {
            }
        }

        return false;
    }

    private static synchronized
    List<MediaCodecInfo.VideoCapabilities>
    getAvcEncoders() {

        if (avcEncoders != null) {
            return avcEncoders;
        }

        List<MediaCodecInfo.VideoCapabilities> list =
                new ArrayList<>();

        try {

            MediaCodecInfo[] codecInfos =
                    new MediaCodecList(
                            MediaCodecList.REGULAR_CODECS
                    ).getCodecInfos();

            for (MediaCodecInfo info : codecInfos) {

                if (!info.isEncoder()) {
                    continue;
                }

                for (String type :
                        info.getSupportedTypes()) {

                    if (!MediaFormat.MIMETYPE_VIDEO_AVC
                            .equalsIgnoreCase(type)) {
                        continue;
                    }

                    try {

                        MediaCodecInfo.VideoCapabilities vc =
                                info.getCapabilitiesForType(type)
                                        .getVideoCapabilities();

                        if (vc != null) {
                            list.add(vc);
                        }

                    } catch (RuntimeException ignored) {
                        // One broken codec must not stop the others.
                    }
                }
            }

        } catch (RuntimeException ignored) {
        }

        avcEncoders = list;

        return list;
    }

    // ---------------------------------------------------------------------------------
    // Flash, zoom, anti-flicker
    // ---------------------------------------------------------------------------------

    public static boolean hasFlash(
            @NonNull CameraCharacteristics c
    ) {

        Boolean available =
                c.get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                );

        return available != null && available;
    }

    /** Digital zoom limit of this camera, capped at 4x. */
    public static float maxZoom(
            @NonNull CameraCharacteristics c
    ) {

        Float max =
                c.get(
                        CameraCharacteristics
                                .SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                );

        if (max == null) {
            return 1.0f;
        }

        return Math.min(
                max,
                MAX_ZOOM
        );
    }

    /**
     * AUTO anti-banding (the phone picks 50/60 Hz itself)
     * if supported, otherwise null.
     */
    @Nullable
    public static Integer pickAntibandingMode(
            @NonNull CameraCharacteristics c
    ) {

        int[] modes =
                c.get(
                        CameraCharacteristics
                                .CONTROL_AE_AVAILABLE_ANTIBANDING_MODES
                );

        if (modes == null) {
            return null;
        }

        for (int mode : modes) {

            if (mode ==
                    CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO) {

                return mode;
            }
        }

        return null;
    }
        }
