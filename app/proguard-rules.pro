# Keep NewPipeExtractor (reflection / generated stream extractors)
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.schabi.newpipe.extractor.services.** { *; }

# Rhino (JS engine used by NewPipeExtractor) optional desktop-only classes
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.engine.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Media3
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Kotlinx coroutines
-dontwarn kotlinx.coroutines.**
