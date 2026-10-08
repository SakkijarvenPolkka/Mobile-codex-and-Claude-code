/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Java mirror of the Kotlin `fun interface EngineListener`
 * (engine/src/main/java/.../engine/NativeBridge.kt) for the ctest of
 * native/jni. Same name and method signature: JNI looks up
 * onEvent(Ljava/lang/String;[B)V.
 */
package io.github.sakkijarvenpolkka.audacity.engine;

public interface EngineListener {
    void onEvent(String type, byte[] payload);
}
