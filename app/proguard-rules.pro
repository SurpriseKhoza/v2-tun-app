# gomobile / libXray JNI bridge
-keep class go.** { *; }
-keep class libXray.** { *; }
-keepclassmembers class libXray.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
