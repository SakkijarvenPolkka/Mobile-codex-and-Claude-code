/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * ctest `bridge-jni`: drives libaudacity-jni.so through the Java mirror of
 * NativeBridge in a plain JVM started with -Xcheck:jni. Covers argument
 * checking before the engine runs, start/stop/restart (engine thread
 * attach/detach), UTF-8 in both directions, blocking dialogs, multi-choice
 * replies, progress cancel, the display calls, the lock-free reads and a
 * listener that throws.
 *
 *   java -Xcheck:jni -Daudacity.jni.library=<lib>/libaudacity-jni.so \
 *        -cp aujni-test-classes.jar io.github.sakkijarvenpolkka.audacity.engine.JniBridgeTest
 */
package io.github.sakkijarvenpolkka.audacity.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JniBridgeTest {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean condition, String what) {
        ++checks;
        if (!condition) {
            ++failures;
            System.out.println("FAILED: " + what);
        }
    }

    static final class Event {
        final String type;
        final String json;
        final String thread;
        final boolean daemon;

        Event(String type, String json, String thread, boolean daemon) {
            this.type = type;
            this.json = json;
            this.thread = thread;
            this.daemon = daemon;
        }
    }

    /** Records events; optionally throws from onEvent once (the glue must survive it). */
    static final class Recorder implements EngineListener {
        private final List<Event> events = new ArrayList<>();
        volatile boolean throwOnce = false;
        volatile int thrown = 0;

        @Override
        public void onEvent(String type, byte[] payload) {
            final Event e = new Event(type, new String(payload, StandardCharsets.UTF_8),
                Thread.currentThread().getName(), Thread.currentThread().isDaemon());
            synchronized (this) {
                events.add(e);
                notifyAll();
            }
            if (throwOnce) {
                throwOnce = false;
                ++thrown;
                throw new IllegalStateException("listener failure (expected by the test)");
            }
        }

        /** First event at index >= from that matches; null on timeout. */
        synchronized Event waitFor(int from, Predicate<Event> match, long timeoutMs) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            int i = from;
            while (true) {
                for (; i < events.size(); ++i)
                    if (match.test(events.get(i)))
                        return events.get(i);
                final long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0)
                    return null;
                wait(left);
            }
        }

        synchronized int size() {
            return events.size();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String call(NativeBridge b, String command, String args) {
        final byte[] r = b.invoke(utf8(command), utf8(args));
        return r == null ? "null" : new String(r, StandardCharsets.UTF_8);
    }

    private static boolean ok(String envelope) {
        return envelope.startsWith("{") && envelope.contains("\"ok\":true");
    }

    private static long number(String json, String key) {
        final Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Event waitType(Recorder r, int from, String type, long timeoutMs) throws InterruptedException {
        return r.waitFor(from, e -> e.type.equals(type), timeoutMs);
    }

    private static Event waitReadyOrFailed(Recorder r, int from) throws InterruptedException {
        return r.waitFor(from, e -> e.type.equals("engine.ready") || e.type.equals("engine.failed"), 120_000);
    }

    private static boolean engineThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(t -> t.getName().equals("AudacityEngine") && t.isAlive());
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // best effort
        }
    }

    // -----------------------------------------------------------------------

    private static void beforeStart(NativeBridge b) {
        check(!b.readTransport(new double[16]), "readTransport is false before start");
        check(!b.readMeters(new float[14]), "readMeters is false before start");
        check(!b.readTransport(new double[3]), "readTransport rejects a short array");
        check(!b.readTransport(null), "readTransport rejects null");
        check(!b.readMeters(null), "readMeters rejects null");
        final String r = call(b, "project.new", "{}");
        check(r.contains("\"NOT_READY\""), "invoke before start answers NOT_READY: " + r);
        final byte[] nullCommand = b.invoke(null, null);
        check(nullCommand != null && new String(nullCommand, StandardCharsets.UTF_8).contains("\"INVALID_ARGS\""),
            "invoke(null) answers INVALID_ARGS");
        check(b.waveColumns(0, 0, 0, 0, 256, new float[768]) == -3, "waveColumns before start is -3");
        check(b.waveColumns(0, 0, 0, 0, 256, null) == -1, "waveColumns(null) is -1");
        check(b.waveColumns(0, 0, 0, 0, 256, new float[767]) == -1, "waveColumns with a short array is -1");
        check(b.waveColumns(0, 0, 0, 0, 0, new float[3]) == -1, "waveColumns(count 0) is -1");
        check(b.waveColumns(0, 0, 0, 0, 65537, new float[3]) == -1, "waveColumns(count 65537) is -1");
        check(b.envelopeColumns(0, 0, 0, 4, new float[3]) == -1, "envelopeColumns with a short array is -1");
        check(b.spectrogramColumns(0, 0, 0, 0, 4, 0, new byte[16]) == -1, "spectrogramColumns(rows 0) is -1");
        check(b.spectrogramColumns(0, 0, 0, 0, 4, 4097, new byte[16]) == -1, "spectrogramColumns(rows 4097) is -1");
        check(b.spectrogramColumns(0, 0, 0, 0, 4, 4, new byte[15]) == -1, "spectrogramColumns with a short array is -1");
        check(b.waveSamples(0, 0, 0.0, 1.0) == null, "waveSamples before start is null");
        // Unknown ids are ignored
        b.replyDialog(12345, 0);
        b.replyDialogChoices(12345, new int[] { 1 });
        b.replyDialogChoices(12345, null);
        b.cancelProgress(12345, false);
    }

    private static void session(NativeBridge b, Recorder rec, String config, boolean full) throws Exception {
        final int base = rec.size();
        check(b.start(utf8(config), rec), "start");
        check(!b.start(utf8(config), rec), "a second start while running is refused");
        final Event ready = waitReadyOrFailed(rec, base);
        check(ready != null && ready.type.equals("engine.ready"),
            "engine.ready (got " + (ready == null ? "timeout" : ready.type + " " + ready.json) + ")");
        if (ready == null || !ready.type.equals("engine.ready"))
            return;
        check(ready.thread.equals("AudacityEngine"), "events come from the engine thread (" + ready.thread + ")");
        check(ready.daemon, "the engine thread is attached as a daemon thread");
        check(engineThreadAlive(), "the attached engine thread is visible to the JVM");

        String r = call(b, "project.new", "{}");
        check(ok(r), "project.new: " + r);
        if (!full)
            return;

        // UTF-8 in arguments and results (not modified UTF-8)
        final String text = "테스트 🎵 café";   // 테스트 🎵 café
        r = call(b, "project.tags.set", "{\"tags\":[{\"name\":\"TITLE\",\"value\":" + quote(text) + "}]}");
        check(ok(r), "project.tags.set: " + r);
        r = call(b, "project.tags.get", "{}");
        check(r.contains(text), "project.tags.get returns the UTF-8 text: " + r);

        // A track; its snapshot event carries a UTF-8 name
        int mark = rec.size();
        r = call(b, "debug.makeTestTrack", "{\"seconds\":1,\"frequency\":440,\"amplitude\":0.5}");
        check(ok(r), "debug.makeTestTrack: " + r);
        final long trackId = number(r, "id");
        check(trackId >= 0, "track id " + trackId);
        check(rec.waitFor(mark, e -> e.type.equals("snapshot") && e.json.contains("Test Tone"), 5000) != null,
            "the snapshot of makeTestTrack arrived before the response");
        mark = rec.size();
        r = call(b, "tracks.rename", "{\"id\":" + trackId + ",\"name\":" + quote(text) + "}");
        if (ok(r))
            check(rec.waitFor(mark, e -> e.type.equals("snapshot") && e.json.contains(text), 5000) != null,
                "the snapshot event carries the UTF-8 track name");
        else
            System.out.println("note: tracks.rename unavailable: " + r);

        // A listener that throws must not break the engine
        rec.throwOnce = true;
        r = call(b, "history.undo", "{}");
        check(ok(r), "history.undo: " + r);
        check(rec.thrown == 1, "the listener threw once");
        r = call(b, "history.redo", "{}");
        check(ok(r), "history.redo after a listener exception: " + r);

        // Display calls (display module): level 80 = 1024 px/s, column mode
        final float[] columns = new float[3 * 256];
        java.util.Arrays.fill(columns, 42f);
        final long wave = b.waveColumns(trackId, 0, 80, 0, 256, columns);
        if (wave >= 0) {
            final float min0 = columns[0], max0 = columns[256];
            check(min0 <= max0 && max0 > 0.1f && max0 <= 0.51f && min0 >= -0.51f,
                "waveColumns column 0: min " + min0 + " max " + max0);
            check(Float.isNaN(columns[255]) || columns[255] != 42f, "waveColumns filled the whole tile");
        } else {
            check(wave == -3, "waveColumns: version or -3 (got " + wave + ")");
            System.out.println("note: waveColumns returned " + wave);
        }
        check(b.waveColumns(trackId, 7, 80, 0, 256, columns) == -1, "waveColumns of a missing channel is -1");
        final float[] envelope = new float[256];
        final long env = b.envelopeColumns(trackId, 80, 0, 256, envelope);
        if (env >= 0)
            check(Math.abs(envelope[0] - 1f) < 1e-6, "envelope gain 1 at column 0 (" + envelope[0] + ")");
        else
            check(env == -3, "envelopeColumns: version or -3 (got " + env + ")");
        final byte[] samples = b.waveSamples(trackId, 0, 0.0, 0.001);
        if (samples != null)
            check(samples.length >= 4 && samples[0] == 1 && samples[1] == 0, "waveSamples: one run");
        final byte[] spectrum = new byte[16 * 64];
        final long spec = b.spectrogramColumns(trackId, 0, 80, 0, 16, 64, spectrum);
        check(spec >= 0 || spec == -3 || spec == -5, "spectrogramColumns: version, -3 or -5 (got " + spec + ")");

        // Lock-free reads (audio module); false is acceptable without it
        final double[] transport = new double[16];
        final boolean haveTransport = b.readTransport(transport);
        if (haveTransport)
            check(transport[0] == 0.0, "transport state stopped (" + transport[0] + ")");
        final float[] meters = new float[14];
        b.readMeters(meters);

        // Blocking question answered from another thread
        mark = rec.size();
        CompletableFuture<String> pending = CompletableFuture.supplyAsync(
            () -> call(b, "debug.ask", "{\"message\":\"Question?\",\"title\":\"JNI\"}"));
        Event dialog = rec.waitFor(mark, e -> e.type.equals("dialog") && e.json.contains("Question?"), 10_000);
        check(dialog != null && dialog.json.contains("\"blocking\":true"), "blocking dialog event");
        if (dialog != null) {
            b.replyDialog((int) number(dialog.json, "id"), 0);
            r = pending.get(10, TimeUnit.SECONDS);
            check(r.contains("\"yes\""), "debug.ask answered 'yes': " + r);
        }

        // Multi-choice: duplicates and out-of-range indices are dropped
        mark = rec.size();
        pending = CompletableFuture.supplyAsync(() -> call(b, "debug.ask",
            "{\"multiChoice\":true,\"message\":\"Streams\",\"choices\":[\"a\",\"b\",\"c\"],"
                + "\"defaultChecked\":[true,false,true]}"));
        dialog = rec.waitFor(mark, e -> e.type.equals("dialog") && e.json.contains("multiChoice"), 10_000);
        check(dialog != null, "multiChoice dialog event");
        if (dialog != null) {
            b.replyDialogChoices((int) number(dialog.json, "id"), new int[] { 2, 0, 2, 7, -1 });
            r = pending.get(10, TimeUnit.SECONDS);
            check(r.replace(" ", "").contains("\"choices\":[0,2]"), "replyDialogChoices: " + r);
        }

        // Progress cancel
        mark = rec.size();
        final long t0 = System.nanoTime();
        pending = CompletableFuture.supplyAsync(() -> call(b, "debug.progress", "{\"seconds\":20}"));
        final Event progress = rec.waitFor(mark, e -> e.type.equals("progress") && e.json.contains("\"begin\""), 10_000);
        check(progress != null, "progress begin event");
        if (progress != null) {
            b.cancelProgress((int) number(progress.json, "id"), false);
            r = pending.get(15, TimeUnit.SECONDS);
            check(r.contains("\"CANCELLED\""), "debug.progress cancelled: " + r);
            check(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(15), "cancel was prompt");
        }

        // Unknown command and malformed arguments go through the bridge
        r = call(b, "no.such.command", "{}");
        check(r.contains("\"UNKNOWN_COMMAND\""), "unknown command: " + r);
        r = call(b, "project.info", "[1,2]");
        check(r.contains("\"INVALID_ARGS\""), "non-object args: " + r);
    }

    public static void main(String[] args) throws Exception {
        final String library = System.getProperty("audacity.jni.library");
        if (library == null || library.isEmpty()) {
            System.out.println("usage: -Daudacity.jni.library=<path>/libaudacity-jni.so");
            System.exit(2);
        }
        NativeBridge.load(library);
        final NativeBridge b = NativeBridge.INSTANCE;

        beforeStart(b);

        final Path root = Files.createTempDirectory("aujni-test");
        final Path files = Files.createDirectories(root.resolve("files"));
        final Path noBackup = Files.createDirectories(root.resolve("no_backup"));
        final Path cache = Files.createDirectories(root.resolve("cache"));
        final String config = "{\"filesDir\":" + quote(files.toString())
            + ",\"noBackupDir\":" + quote(noBackup.toString())
            + ",\"cacheDir\":" + quote(cache.toString())
            + ",\"locale\":\"en_US\",\"deviceModel\":\"jni-test\",\"audioOutputSampleRate\":48000,"
            + "\"audioFramesPerBuffer\":192,\"recordPermission\":false}";
        final Recorder rec = new Recorder();
        check(!b.start(utf8(config), null), "start with a null listener is refused");
        try {
            session(b, rec, config, true);
        } finally {
            b.stop();
        }
        check(call(b, "project.new", "{}").contains("\"NOT_READY\""), "invoke after stop answers NOT_READY");
        check(!b.readTransport(new double[16]), "readTransport is false after stop");
        check(!engineThreadAlive(), "the engine thread was detached when it exited");

        // Restart in the same process with the same directories: a new
        // engine thread is attached again
        final Recorder rec2 = new Recorder();
        try {
            session(b, rec2, config, false);
        } finally {
            b.stop();
        }
        check(!engineThreadAlive(), "the second engine thread was detached too");
        final int eventsAfterStop = rec2.size();
        Thread.sleep(200);
        check(rec2.size() == eventsAfterStop, "no events after stop");

        deleteTree(root);
        System.out.println("bridge-jni: " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
