/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.ai;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live "video" of the agent's Minecraft window: grabs the already-rendered main framebuffer at a
 * throttled rate and drops downscaled JPEG frames into {@code <gameDir>/video/frames/}. The launcher
 * tails that directory, pipes the frames into ffmpeg as an MJPEG stream, and serves H.264 LL-HLS to
 * the launcher UI + phone. Like {@link ScreenshotHelper} it never opens a Screen, so Baritone keeps
 * running uninterrupted.
 *
 * <p>Hard-won correctness (verified against 1.21.11 mojmap + adversarial review):
 * <ul>
 *   <li><b>AFK throttle:</b> an unattended client drops to ~10fps after 60s and ~1fps after 10min
 *       ({@code FramerateLimitTracker}). Every grab calls {@code onInputReceived()} so the render loop
 *       keeps running at the configured cap while we're capturing.</li>
 *   <li><b>Off the render thread:</b> the render-thread closure only copies pixels out
 *       ({@code getPixelsABGR}) and closes the image; downscale + JPEG encode + disk write + prune all
 *       run on a single background thread.</li>
 *   <li><b>Backpressure:</b> a single in-flight frame at a time ({@code inFlight}); a scheduler tick
 *       that fires while a frame is still encoding is dropped, so GPU readbacks never pile up.</li>
 *   <li><b>Watchdog:</b> auto-stops after {@code MAX_DURATION_MS} so a lost "live off" can't leave the
 *       capture (and its render-thread tax) running forever.</li>
 *   <li>Never throws into the caller — a capture failure must not be able to fail a mission.</li>
 * </ul>
 */
public final class LiveVideoCapture {

    private static final long MAX_DURATION_MS = 600_000L; // 10 min backstop if "live off" is lost
    private static final int KEEP_FRAMES = 30;            // rolling window the mod itself prunes

    private static volatile boolean running = false;
    private static ScheduledExecutorService scheduler;
    private static ExecutorService encoder;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final AtomicInteger counter = new AtomicInteger(0);

    private static int scale = 2;
    private static String ext = "jpg";
    private static Path framesDir;
    private static long startedAt;

    private LiveVideoCapture() {
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * Start capturing. Idempotent — a second start while already running is a no-op.
     *
     * @param fps   target frames per second (clamped 2..15)
     * @param scl   integer downscale factor applied off-thread (clamped 1..8)
     */
    public static synchronized void start(int fps, int scl) {
        if (running) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null || mc.getMainRenderTarget() == null) {
                return;
            }
            int f = Math.max(2, Math.min(15, fps));
            scale = Math.max(1, Math.min(8, scl));
            // JPEG via ImageIO keeps frames small; if the writer is somehow unavailable, bail rather
            // than ship a half-working stream (the screenshot check-in still works regardless).
            try {
                if (!ImageIO.getImageWritersByFormatName("jpg").hasNext()) {
                    AgentTelemetry.emit("live_video_stop", Map.of("reason", "no_jpeg_encoder"));
                    return;
                }
            } catch (Throwable t) {
                AgentTelemetry.emit("live_video_stop", Map.of("reason", "imageio_unavailable"));
                return;
            }
            ext = "jpg";

            framesDir = mc.gameDirectory.toPath().resolve("video").resolve("frames");
            Files.createDirectories(framesDir);
            cleanDir(framesDir);
            counter.set(0);
            inFlight.set(false);
            startedAt = System.currentTimeMillis();

            encoder = Executors.newSingleThreadExecutor(r -> daemon(r, "kihi-live-encoder"));
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "kihi-live-scheduler"));
            running = true;
            long periodMs = Math.max(33L, 1000L / f);
            scheduler.scheduleAtFixedRate(LiveVideoCapture::tick, 0, periodMs, TimeUnit.MILLISECONDS);
            AgentTelemetry.emit("live_video_start", Map.of("fps", f, "scale", scale, "format", "mjpeg"));
        } catch (Throwable t) {
            // never let live capture break a mission
            stop("start_error");
        }
    }

    /** Stop capturing and tear down the worker threads. Idempotent. */
    public static synchronized void stop(String reason) {
        if (!running && scheduler == null && encoder == null) {
            return;
        }
        running = false;
        try {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (encoder != null) {
                encoder.shutdownNow();
            }
        } catch (Throwable ignored) {
        }
        scheduler = null;
        encoder = null;
        inFlight.set(false);
        try {
            AgentTelemetry.emit("live_video_stop", Map.of("reason", reason == null ? "stop" : reason));
        } catch (Throwable ignored) {
        }
    }

    // ── capture loop ─────────────────────────────────────────────────────────

    private static void tick() {
        if (!running) {
            return;
        }
        if (System.currentTimeMillis() - startedAt > MAX_DURATION_MS) {
            stop("max_duration");
            return;
        }
        // skip-if-busy: only one frame in flight end-to-end (grab + encode), so GPU readbacks and
        // encode work can never pile up behind a busy render thread.
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                inFlight.set(false);
                return;
            }
            mc.execute(() -> grabOnRenderThread(mc));
        } catch (Throwable t) {
            inFlight.set(false);
        }
    }

    private static void grabOnRenderThread(Minecraft mc) {
        try {
            if (!running || mc.getMainRenderTarget() == null) {
                inFlight.set(false);
                return;
            }
            // keep the render loop awake at the configured cap (defeat the AFK framerate throttle)
            try {
                mc.getFramerateLimitTracker().onInputReceived();
            } catch (Throwable ignored) {
            }
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
                // runs once the GPU readback completes; copy pixels out and free the image promptly
                int w, h;
                int[] abgr;
                try {
                    w = image.getWidth();
                    h = image.getHeight();
                    abgr = image.getPixelsABGR();
                } catch (Throwable t) {
                    safeClose(image);
                    inFlight.set(false);
                    return;
                }
                safeClose(image);
                ExecutorService enc = encoder;
                if (!running || enc == null) {
                    inFlight.set(false);
                    return;
                }
                enc.submit(() -> {
                    try {
                        encodeAndWrite(abgr, w, h);
                    } catch (Throwable ignored) {
                    } finally {
                        inFlight.set(false); // clear only after the frame is fully written → natural pacing
                    }
                });
            });
        } catch (Throwable t) {
            inFlight.set(false);
        }
    }

    // ── encoder thread ─────────────────────────────────────────────────────────

    private static void encodeAndWrite(int[] abgr, int w, int h) throws Exception {
        if (w <= 0 || h <= 0 || abgr == null) {
            return;
        }
        // even dimensions: libx264 + yuv420p (4:2:0) reject odd width/height
        int outW = Math.max(2, (w / scale) & ~1);
        int outH = Math.max(2, (h / scale) & ~1);
        BufferedImage img = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outH; y++) {
            int sy = y * scale;
            int rowBase = sy * w;
            for (int x = 0; x < outW; x++) {
                int p = abgr[rowBase + x * scale]; // ABGR packed: A<<24 | B<<16 | G<<8 | R
                int r = p & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = (p >> 16) & 0xFF;
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        int n = counter.incrementAndGet();
        Path tmp = framesDir.resolve(String.format("frame_%06d.%s.tmp", n, ext));
        Path dst = framesDir.resolve(String.format("frame_%06d.%s", n, ext));
        try (var os = Files.newOutputStream(tmp)) {
            ImageIO.write(img, "jpg", os);
        }
        try {
            Files.move(tmp, dst); // atomic-ish rename so the launcher never reads a half-written frame
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            return;
        }
        // best-effort prune (the launcher's feeder also deletes consumed frames)
        Path old = framesDir.resolve(String.format("frame_%06d.%s", n - KEEP_FRAMES, ext));
        try {
            Files.deleteIfExists(old);
        } catch (Exception ignored) {
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void cleanDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                stream.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static void safeClose(NativeImage image) {
        try {
            image.close();
        } catch (Throwable ignored) {
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
