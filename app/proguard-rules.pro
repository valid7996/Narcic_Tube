# Add project specific ProGuard rules here.
# Kotlin coroutines
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn kotlinx.coroutines.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
