# Keep Room generated implementations.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep WebView JavaScript interface bridge (referenced reflectively by WebView).
-keepclassmembers class com.lumobrowser.app.browser.LumoJsBridge {
    public *;
}

# Kotlin metadata / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { public <methods>; }
