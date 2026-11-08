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

/**
 * Keeps the AVERAGE video bitrate under a limit even when the phone's encoder is sloppy.
 *
 * Phone encoders treat the bitrate we ask for as a hint: busy scenes can come out at twice the
 * asked value, calm ones at half. This class watches how many bytes really come out and moves
 * the asked bitrate up or down until the real numbers match the limit.
 *
 * Plain Java (no Android classes) so it can be tested on a normal computer.
 */
final class BitrateController {

    private static final long WINDOW_US = 2_000_000L;
    private static final long CHECK_EVERY_US = 1_000_000L;
    private static final long WARMUP_US = 1_000_000L;
    private static final double MIN_STEP = 0.75;   // never cut more than 25% in one go
    private static final double MAX_STEP = 1.10;   // and raise by 10% at most

    private final int target;
    private final int floor;
    private int request;

    // history of (time, bytes so far): a ring buffer, nothing is allocated per frame
    private static final int RING = 512;
    private final long[] ringPts = new long[RING];
    private final long[] ringBytes = new long[RING];
    private int ringHead = 0;
    private int ringSize = 0;

    private long totalBytes;
    private long lastCheckUs;
    private double peakBps;

    BitrateController(int targetBps) {
        this.target = targetBps;
        this.floor = Math.max(300_000, targetBps / 4);
        this.request = targetBps;
        add(0, 0);
    }

    private void add(long pts, long bytesSoFar) {
        if (ringSize == RING) {
            ringHead = (ringHead + 1) % RING;
            ringSize--;
        }
        int at = (ringHead + ringSize) % RING;
        ringPts[at] = pts;
        ringBytes[at] = bytesSoFar;
        ringSize++;
    }

    int currentRequest() {
        return request;
    }

    double averageBps(long nowUs) {
        return nowUs <= 0 ? 0 : totalBytes * 7_500_000.0 / nowUs;
    }

    double peakBps() {
        return peakBps;
    }

    long totalBytes() {
        return totalBytes;
    }

    /**
     * Call once for every video frame that was written.
     *
     * @return the new bitrate to ask the encoder for, or -1 when nothing needs to change
     */
    int onFrame(long ptsUs, int bytes) {
        totalBytes += bytes;
        add(ptsUs, totalBytes);

        // keep only about WINDOW_US of history (plus one older sample to measure against)
        while (ringSize > 2 && ringPts[(ringHead + 1) % RING] < ptsUs - WINDOW_US) {
            ringHead = (ringHead + 1) % RING;
            ringSize--;
        }

        if (ptsUs < WARMUP_US || ptsUs - lastCheckUs < CHECK_EVERY_US) {
            return -1;
        }
        lastCheckUs = ptsUs;

        long span = ptsUs - ringPts[ringHead];
        if (span <= 0) return -1;
        double windowBps = (totalBytes - ringBytes[ringHead]) * 8_000_000.0 / span;
        if (windowBps > peakBps) peakBps = windowBps;

        // If the file so far is over the limit, aim lower for a while to pay it back.
        double average = averageBps(ptsUs);
        double debt = Math.max(0, (average - target) / target);
        double aim = target * (1.0 - Math.min(0.5, debt));

        double ratio = aim / Math.max(windowBps, 1.0);
        ratio = Math.max(MIN_STEP, Math.min(MAX_STEP, ratio));

        int next = (int) Math.round(request * ratio);
        next = Math.max(floor, Math.min(target, next));

        if (Math.abs(next - request) < request * 0.02) return -1;
        request = next;
        return next;
    }
}

