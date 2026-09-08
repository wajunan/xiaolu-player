# Keep Media3 / ExoPlayer classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# Keep kotlinx.serialization generated serializers
-keepclassmembers class com.panplayer.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.panplayer.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.panplayer.app.**$$serializer { *; }
-keepclassmembers class com.panplayer.app.** {
    *** INSTANCE;
}
