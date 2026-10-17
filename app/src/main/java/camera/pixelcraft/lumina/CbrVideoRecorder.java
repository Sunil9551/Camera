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

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Records H.264 video + AAC audio into an MP4 with a bitrate that stays put.
 *
 * Why this exists: MediaRecorder only treats the bitrate as a hint. Most phones then run
 * the encoder in VBR mode, so a busy outdoor scene can double the file size while a calm
 * indoor scene halves it. Here the encoder is asked for CBR (constant bitrate) directly.
 *
 * Phones whose encoder cannot do CBR get a second safety net: the real bitrate written to
 * the file is measured every second and the encoder is told to slow down whenever the
 * running average goes above the target.
 *
 * The public methods have the same names and order as MediaRecorder
 * (setOutputFile, prepare, getSurface, start, pause, resume, stop, release),
 * so MainActivity changes very little.
 */
public final class CbrVideoRecorder {

    private static final String TAG = "CbrVideoRecorder";

    private static final String VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC;

    private static final int AUDIO_SAMPLE_RATE = 44_100;
    private static final int AUDIO_READ_BYTES = 4096;

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    // If the microphone is not ready this soon after the first video frame,
    // the clip is recorded without sound instead of not recording at all.
    private static final long AUDIO_WAIT_MS = 3_000L;

    private static final long STOP_JOIN_MS = 4_000L;
    private static final long RELEASE_JOIN_MS = 1_000L;

    // Bitrate guard: start checking after 3 s, then once per second of recorded video.
    private static final long GUARD_WARMUP_US = 3_000_000L;
    private static final long GUARD_INTERVAL_US = 1_000_000L;
    private static final float GUARD_MIN_FACTOR = 0.6f;

    private final int width;
    private final int height;
    private final int frameRate;
    private final int videoBitrate;
    private final int orientationHint;
    private boolean withAudio;

    @Nullable
    private FileDescriptor outputFd;
    @Nullable
    private String outputPath;

    private MediaCodec videoCodec;
    private Surface inputSurface;
    private MediaCodec audioCodec;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;

    private Thread videoThread;
    private Thread audioThread;

    // Muxer state - everything below is guarded by muxerLock.
    private final Object muxerLock = new Object();
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted = false;
    private boolean audioActive = false;

    private boolean prepared = false;
    private boolean started = false;
    private boolean stopped = false;
    private boolean released = false;

    private volatile boolean stopRequested = false;
    private volatile boolean failed = false;
    private volatile boolean paused = false;
    private volatile boolean waitingForKeyFrame = false;
    private volatile long pausedTotalUs = 0L;
    private volatile long videoSamples = 0L;

    private long pauseStartNanos = 0L;
    private long lastAudioPtsUs = -1L;

    /**
     * @param videoBitrate video bits per second. Audio (128 kbps) is added on top,
     *                     so pass (total limit - audio).
     */
    public CbrVideoRecorder(
            int width,
            int height,
            int frameRate,
            int videoBitrate,
            int orientationHint,
            boolean withAudio
    ) {
        this.width = width;
        this.height = height;
        this.frameRate = Math.max(1, frameRate);
        this.videoBitrate = videoBitrate;
        this.orientationHint = ((orientationHint % 360) + 360) % 360;
        this.withAudio = withAudio;
    }

    // ---------------------------------------------------------------------------------
    // Output
    // ---------------------------------------------------------------------------------

    /** The descriptor must be opened read + write ("rw"). */
    public void setOutputFile(@NonNull FileDescriptor fd) {
        this.outputFd = fd;
        this.outputPath = null;
    }

    public void setOutputFile(@NonNull String path) {
        this.outputPath = path;
        this.outputFd = null;
    }

    // ---------------------------------------------------------------------------------
    // Prepare
    // ---------------------------------------------------------------------------------

    public void prepare() throws IOException {
        if (prepared) {
            throw new IllegalStateException("Already prepared");
        }
        if (outputFd == null && outputPath == null) {
            throw new IOException("No output file set");
        }

        try {
            setUpVideoEncoder();

            if (withAudio) {
                try {
                    setUpAudio();
                } catch (IOException | RuntimeException e) {
                    // No microphone is not a reason to refuse recording video.
                    Log.w(TAG, "Recording without sound: " + e.getMessage());
                    releaseAudio();
                    withAudio = false;
                }
            }

            setUpMuxer();

            synchronized (muxerLock) {
                audioActive = withAudio;
            }
            prepared = true;

        } catch (IOException | RuntimeException e) {
            release();
            throw e;
        }
    }

    private void setUpVideoEncoder() throws IOException {
        MediaCodec codec = MediaCodec.createEncoderByType(VIDEO_MIME);
        boolean cbr = supportsCbr(codec);

        try {
            codec.configure(
                    buildVideoFormat(cbr),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );
        } catch (RuntimeException e) {
            codec.release();

            if (!cbr) {
                throw e;
            }

            // Some phones say they support CBR and then refuse it. Try plain mode.
            Log.w(TAG, "CBR rejected, using guarded VBR: " + e.getMessage());
            cbr = false;
            codec = MediaCodec.createEncoderByType(VIDEO_MIME);
            codec.configure(
                    buildVideoFormat(false),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
            );
        }

        Log.i(TAG, "Video encoder: " + (cbr ? "CBR" : "VBR + guard")
                + " @ " + videoBitrate + " bps");

        inputSurface = codec.createInputSurface();
        videoCodec = codec;
    }

    private MediaFormat buildVideoFormat(boolean cbr) {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (cbr) {
            format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            );
        }
        return format;
    }

    private static boolean supportsCbr(MediaCodec codec) {
        try {
            MediaCodecInfo.EncoderCapabilities caps =
                    codec.getCodecInfo()
                            .getCapabilitiesForType(VIDEO_MIME)
                            .getEncoderCapabilities();

            return caps != null && caps.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            );
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void setUpAudio() throws IOException {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                channelConfig,
                encoding
        );
        if (minBuffer <= 0) {
            throw new IOException("Microphone is not available");
        }

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                channelConfig,
                encoding,
                Math.max(minBuffer * 2, AUDIO_READ_BYTES * 4)
        );
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("Microphone could not be opened");
        }

        MediaFormat format = MediaFormat.createAudioFormat(
                AUDIO_MIME,
                AUDIO_SAMPLE_RATE,
                1
        );
        format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
        );
        format.setInteger(
                MediaFormat.KEY_BIT_RATE,
                CameraCapabilities.AUDIO_BITRATE
        );
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_READ_BYTES * 4);

        audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME);
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    private void setUpMuxer() throws IOException {
        if (outputFd != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                throw new IOException("Writing to a descriptor needs Android 8.0 or newer");
            }
            muxer = new MediaMuxer(
                    outputFd,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
        } else {
            muxer = new MediaMuxer(
                    outputPath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
        }
        muxer.setOrientationHint(orientationHint);
    }

    /** The camera draws into this surface. Valid after prepare(). */
    public Surface getSurface() {
        return inputSurface;
    }

    // ---------------------------------------------------------------------------------
    // Start / pause / resume / stop
    // ---------------------------------------------------------------------------------

    public void start() {
        if (!prepared || started) {
            throw new IllegalStateException("Recorder is not ready to start");
        }

        videoCodec.start();
        if (withAudio) {
            audioCodec.start();
        }
        started = true;

        videoThread = new Thread(this::videoLoop, "cbr-video");
        videoThread.start();

        if (withAudio) {
            audioThread = new Thread(this::audioLoop, "cbr-audio");
            audioThread.start();
        }
    }

    public void pause() {
        if (!started || stopped || paused) {
            return;
        }
        pauseStartNanos = System.nanoTime();
        paused = true;
    }

    public void resume() {
        if (!started || stopped || !paused) {
            return;
        }

        // Video frames written after the pause must not point back at frames we threw
        // away, so wait for a fresh key frame and ask the encoder for one right now.
        pausedTotalUs += (System.nanoTime() - pauseStartNanos) / 1000L;
        waitingForKeyFrame = true;
        requestKeyFrame();
        paused = false;
    }

    private void requestKeyFrame() {
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            videoCodec.setParameters(params);
        } catch (RuntimeException ignored) {
            // The next regular key frame (at most 1 s away) does the same job.
        }
    }

    /**
     * Finishes the file. Throws IllegalStateException when nothing usable was recorded,
     * exactly like MediaRecorder.stop() does when it was stopped too early.
     */
    public void stop() {
        if (!started || stopped) {
            throw new IllegalStateException("Recorder is not running");
        }
        stopped = true;
        stopRequested = true;

        try {
            videoCodec.signalEndOfInputStream();
        } catch (RuntimeException e) {
            Log.w(TAG, "signalEndOfInputStream: " + e.getMessage());
        }

        join(videoThread, STOP_JOIN_MS);
        join(audioThread, STOP_JOIN_MS);

        boolean hadData;
        synchronized (muxerLock) {
            muxerLock.notifyAll();
            hadData = muxerStarted;
            if (muxerStarted) {
                try {
                    muxer.stop();
                } catch (RuntimeException e) {
                    Log.w(TAG, "muxer.stop: " + e.getMessage());
                    failed = true;
                }
                muxerStarted = false;
            }
        }

        if (!hadData || failed || videoSamples == 0L) {
            throw new IllegalStateException("Nothing was recorded");
        }
    }

    private static void join(@Nullable Thread thread, long timeoutMs) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Safe to call at any time, any number of times. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        stopRequested = true;

        if (started && !stopped) {
            try {
                videoCodec.signalEndOfInputStream();
            } catch (RuntimeException ignored) {
            }
            join(videoThread, RELEASE_JOIN_MS);
            join(audioThread, RELEASE_JOIN_MS);
        }

        synchronized (muxerLock) {
            muxerLock.notifyAll();
            if (muxerStarted && muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxerStarted = false;
            }
        }

        releaseCodec(videoCodec);
        videoCodec = null;

        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }

        releaseAudio();

        if (muxer != null) {
            try {
                muxer.release();
            } catch (RuntimeException ignored) {
            }
            muxer = null;
        }
    }

    private void releaseAudio() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (RuntimeException ignored) {
            }
            try {
                audioRecord.release();
            } catch (RuntimeException ignored) {
            }
            audioRecord = null;
        }

        releaseCodec(audioCodec);
        audioCodec = null;
    }

    private void releaseCodec(@Nullable MediaCodec codec) {
        if (codec == null) {
            return;
        }
        if (started) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            codec.release();
        } catch (RuntimeException ignored) {
        }
    }

    // ---------------------------------------------------------------------------------
    // Muxer helpers
    // ---------------------------------------------------------------------------------

    /**
     * Adds a track. The MP4 can only start once every track is known, so this waits
     * for the other one. A missing microphone never blocks the video for more than
     * AUDIO_WAIT_MS - after that the clip is simply recorded without sound.
     */
    private void addTrackAndWait(boolean isVideo, MediaFormat format) {
        synchronized (muxerLock) {
            if (muxerStarted) {
                return; // tracks cannot be added after start
            }

            if (isVideo) {
                videoTrack = muxer.addTrack(format);
            } else {
                if (!audioActive) {
                    return;
                }
                audioTrack = muxer.addTrack(format);
            }

            startMuxerIfReadyLocked();

            long deadline = SystemClock.elapsedRealtime() + AUDIO_WAIT_MS;

            while (!muxerStarted && !failed && !stopRequested) {
                try {
                    if (isVideo) {
                        long left = deadline - SystemClock.elapsedRealtime();
                        if (left <= 0) {
                            audioActive = false;
                            startMuxerIfReadyLocked();
                            break;
                        }
                        muxerLock.wait(left);
                    } else {
                        muxerLock.wait(50);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void startMuxerIfReadyLocked() {
        if (muxerStarted || videoTrack < 0) {
            return;
        }
        if (audioActive && audioTrack < 0) {
            return;
        }
        muxer.start();
        muxerStarted = true;
        muxerLock.notifyAll();
    }

    private void disableAudio() {
        synchronized (muxerLock) {
            audioActive = false;
            try {
                startMuxerIfReadyLocked();
            } catch (RuntimeException e) {
                failed = true;
            }
            muxerLock.notifyAll();
        }
    }

    private boolean writeSample(
            boolean isVideo,
            ByteBuffer data,
            MediaCodec.BufferInfo info
    ) {
        synchronized (muxerLock) {
            if (!muxerStarted) {
                return false;
            }

            int track = isVideo ? videoTrack : audioTrack;
            if (track < 0) {
                return false;
            }

            try {
                muxer.writeSampleData(track, data, info);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "writeSampleData: " + e.getMessage());
                if (isVideo) {
                    failed = true;
                }
                return false;
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Video thread
    // ---------------------------------------------------------------------------------

    private void videoLoop() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        long basePtsUs = -1L;
        long lastPtsUs = -1L;
        long lastGuardUs = 0L;
        long writtenBytes = 0L;
        int liveBitrate = videoBitrate;

        try {
            while (true) {

                int index = videoCodec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrackAndWait(true, videoCodec.getOutputFormat());
                    continue;
                }

                if (index < 0) {
                    continue;
                }

                boolean endOfStream =
                        (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean codecConfig =
                        (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                ByteBuffer data = videoCodec.getOutputBuffer(index);

                if (data != null && info.size > 0 && !codecConfig && !paused) {

                    boolean keyFrame =
                            (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                    if (!waitingForKeyFrame || keyFrame) {
                        waitingForKeyFrame = false;

                        if (basePtsUs < 0) {
                            basePtsUs = info.presentationTimeUs;
                        }

                        // Time starts at 0 and the paused part is cut out.
                        long ptsUs = info.presentationTimeUs - basePtsUs - pausedTotalUs;
                        if (ptsUs <= lastPtsUs) {
                            ptsUs = lastPtsUs + 1;
                        }
                        lastPtsUs = ptsUs;

                        int size = info.size;
                        data.position(info.offset);
                        data.limit(info.offset + size);
                        info.set(info.offset, size, ptsUs, info.flags);

                        if (writeSample(true, data, info)) {
                            writtenBytes += size;
                            videoSamples++;

                            if (ptsUs - lastGuardUs >= GUARD_INTERVAL_US) {
                                lastGuardUs = ptsUs;
                                liveBitrate = guardBitrate(writtenBytes, ptsUs, liveBitrate);
                            }
                        }
                    }
                }

                videoCodec.releaseOutputBuffer(index, false);

                if (endOfStream) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Video encoding stopped: " + e.getMessage());
            failed = true;
        }
    }

    /**
     * The safety net. Compares the real average bitrate with the target and
     * tells the encoder to go lower (or back up) when needed.
     * With a working CBR encoder this almost never changes anything.
     */
    private int guardBitrate(long writtenBytes, long recordedUs, int liveBitrate) {
        if (recordedUs < GUARD_WARMUP_US) {
            return liveBitrate;
        }

        double averageBps = writtenBytes * 8_000_000.0 / recordedUs;
        int next = liveBitrate;

        if (averageBps > videoBitrate) {
            next = (int) Math.max(
                    videoBitrate * GUARD_MIN_FACTOR,
                    liveBitrate * 0.85
            );
        } else if (averageBps < videoBitrate * 0.97 && liveBitrate < videoBitrate) {
            next = (int) Math.min(videoBitrate, liveBitrate * 1.10);
        }

        if (next == liveBitrate) {
            return liveBitrate;
        }

        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next);
            videoCodec.setParameters(params);
            return next;
        } catch (RuntimeException e) {
            return liveBitrate;
        }
    }

    // ---------------------------------------------------------------------------------
    // Audio thread
    // ---------------------------------------------------------------------------------

    private void audioLoop() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final byte[] pcm = new byte[AUDIO_READ_BYTES];

        long samplesFed = 0L;
        boolean inputDone = false;
        boolean outputDone = false;

        try {
            audioRecord.startRecording();

            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone is busy");
            }

            while (!outputDone) {

                if (!inputDone) {
                    if (stopRequested) {
                        int in = audioCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                        if (in >= 0) {
                            audioCodec.queueInputBuffer(
                                    in,
                                    0,
                                    0,
                                    samplesFed * 1_000_000L / AUDIO_SAMPLE_RATE,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                            inputDone = true;
                        }
                    } else {
                        int n = audioRecord.read(pcm, 0, pcm.length);
                        if (n < 0) {
                            throw new IllegalStateException("Microphone read error " + n);
                        }

                        // While paused the microphone keeps running (so nothing piles up)
                        // but the sound is thrown away.
                        if (n > 0 && !paused) {
                            samplesFed = feedAudio(pcm, n, samplesFed, info);
                        }
                    }
                }

                outputDone = drainAudio(info, inputDone);
            }

        } catch (RuntimeException e) {
            // Video keeps going; the clip just has no (more) sound.
            Log.w(TAG, "Audio stopped: " + e.getMessage());
            disableAudio();
        }
    }

    private long feedAudio(
            byte[] pcm,
            int length,
            long samplesFed,
            MediaCodec.BufferInfo info
    ) {
        int offset = 0;

        while (offset < length) {

            int in = audioCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);

            if (in < 0) {
                // Encoder is full - empty its output first.
                drainAudio(info, false);
                if (stopRequested) {
                    return samplesFed;
                }
                continue;
            }

            ByteBuffer buffer = audioCodec.getInputBuffer(in);
            if (buffer == null) {
                throw new IllegalStateException("No audio input buffer");
            }
            buffer.clear();

            // Whole 16-bit samples only.
            int chunk = Math.min(buffer.remaining(), length - offset) & ~1;
            if (chunk <= 0) {
                throw new IllegalStateException("Audio buffer too small");
            }

            buffer.put(pcm, offset, chunk);

            audioCodec.queueInputBuffer(
                    in,
                    0,
                    chunk,
                    samplesFed * 1_000_000L / AUDIO_SAMPLE_RATE,
                    0
            );

            samplesFed += chunk / 2;
            offset += chunk;
        }

        return samplesFed;
    }

    /** Returns true once the encoder has delivered its last buffer. */
    private boolean drainAudio(MediaCodec.BufferInfo info, boolean waitForEnd) {
        long timeoutUs = waitForEnd ? DEQUEUE_TIMEOUT_US : 0L;

        while (true) {

            int index = audioCodec.dequeueOutputBuffer(info, timeoutUs);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false;
            }

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrackAndWait(false, audioCodec.getOutputFormat());
                continue;
            }

            if (index < 0) {
                continue;
            }

            boolean endOfStream =
                    (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            boolean codecConfig =
                    (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

            ByteBuffer data = audioCodec.getOutputBuffer(index);

            if (data != null && info.size > 0 && !codecConfig) {

                long ptsUs = info.presentationTimeUs;
                if (ptsUs <= lastAudioPtsUs) {
                    ptsUs = lastAudioPtsUs + 1;
                }
                lastAudioPtsUs = ptsUs;

                int size = info.size;
                data.position(info.offset);
                data.limit(info.offset + size);
                info.set(info.offset, size, ptsUs, info.flags);

                writeSample(false, data, info);
            }

            audioCodec.releaseOutputBuffer(index, false);

            if (endOfStream) {
                return true;
            }
        }
    }
}
