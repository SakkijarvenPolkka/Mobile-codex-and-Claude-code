# JNI looks up these classes and members by name.
-keep class io.github.sakkijarvenpolkka.audacity.engine.NativeBridge { *; }
-keep interface io.github.sakkijarvenpolkka.audacity.engine.EngineListener { *; }
-keep class * implements io.github.sakkijarvenpolkka.audacity.engine.EngineListener { *; }
