# PSX2 Emulator ProGuard Rules

# Keep native methods - CRITICAL for AAB
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep NativeApp class and all its methods (JNI interface) - CRITICAL
-keep,includedescriptorclasses class com.izzy2lost.psx2.NativeApp {
    public static <methods>;
    public <methods>;
    <fields>;
}

# Keep all static initializers that load native libraries
-keepclassmembers class * {
    static <methods>;
}

# Keep MainActivity for proper lifecycle
-keep class com.izzy2lost.psx2.MainActivity {
    public <methods>;
}

# Keep all classes that might be referenced from native code - CRITICAL for AAB
-keep,includedescriptorclasses class com.izzy2lost.psx2.** { *; }

# Prevent obfuscation of classes used by native code
-keepnames class com.izzy2lost.psx2.** { *; }

# Keep Glide for image loading
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# AAB-specific rules to prevent crashes
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep SDL classes if used
-keep class org.libsdl.** { *; }

# Prevent stripping of native libraries in AAB
-keepclasseswithmembers class * {
    public static native <methods>;
}
