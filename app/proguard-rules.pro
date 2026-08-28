# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** { @kotlinx.serialization.Serializable <methods>; }

# JS bridge — methods called from WebView via @JavascriptInterface must survive R8
-keepclassmembers class dev.kodelab.ide.editor.** {
    @android.webkit.JavascriptInterface <methods>;
}
