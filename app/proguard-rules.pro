# Add project specific ProGuard rules here.
# Kotlin coroutines
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn kotlinx.coroutines.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# youtubedl-android (yt-dlp wrapper): JSON mapping via Jackson + native process
# launching. Minification would strip members it reaches reflectively.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
-dontwarn java.beans.**
-dontwarn javax.xml.stream.**
