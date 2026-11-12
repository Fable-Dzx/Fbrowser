# WebView JS 接口保留
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
