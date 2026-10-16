# Vosk uses JNA, which needs its classes kept if minification is ever enabled.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
