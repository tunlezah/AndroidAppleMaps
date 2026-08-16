# Keep @JavascriptInterface methods reachable from the WebView bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.mapsdroid.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Car App Library
-keep class androidx.car.app.** { *; }
