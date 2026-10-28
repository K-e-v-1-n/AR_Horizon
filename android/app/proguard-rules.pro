# No obfuscation in v1; JNI entry points are named by convention.
-keep class app.vantage.core.NativeCore { *; }
-keep class app.vantage.core.** { *; }
