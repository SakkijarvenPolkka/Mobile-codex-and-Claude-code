/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Java mirror of the Kotlin `object NativeBridge`
 * (engine/src/main/java/.../engine/NativeBridge.kt) for the ctest of
 * native/jni: same class name, instance natives with the same signatures
 * (a Kotlin object compiles to a final class with a static INSTANCE). Keep
 * in sync with NativeBridge.kt and native/jni/AudacityJni.cpp.
 */
package io.github.sakkijarvenpolkka.audacity.engine;

public final class NativeBridge {
    public static final NativeBridge INSTANCE = new NativeBridge();

    private NativeBridge() {
    }

    /** Called from this class so that JNI_OnLoad's FindClass uses its loader. */
    static void load(String path) {
        System.load(path);
    }

    public native boolean start(byte[] config, EngineListener listener);

    public native byte[] invoke(byte[] command, byte[] args);

    public native void replyDialog(int dialogId, int button);

    public native void replyDialogChoices(int dialogId, int[] indices);

    public native void cancelProgress(int progressId, boolean stop);

    public native boolean readTransport(double[] out);

    public native boolean readMeters(float[] out);

    public native long waveColumns(long trackId, int channel, int zoomLevel, long firstColumn, int count, float[] out);

    public native long envelopeColumns(long trackId, int zoomLevel, long firstColumn, int count, float[] out);

    public native byte[] waveSamples(long trackId, int channel, double t0, double t1);

    public native long spectrogramColumns(long trackId, int channel, int zoomLevel, long firstColumn, int count, int rows, byte[] out);

    public native void stop();
}
