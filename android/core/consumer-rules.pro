# JNA and the uniffi bindings are reached through reflection.
-keep class com.sun.jna.** { *; }
-keep class dev.azwall.core.ffi.** { *; }
# JNA's desktop helpers name AWT, which Android lacks; they are never called there.
-dontwarn java.awt.**
