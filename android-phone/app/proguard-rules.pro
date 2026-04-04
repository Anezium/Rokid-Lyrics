# Shared Bluetooth/Gson contracts must keep stable field names across phone/glasses builds.
-keep class com.rokid.lyrics.contracts.** { *; }

# Preserve reflection metadata used by Gson during payload serialization.
-keepattributes Signature,*Annotation*

# Tink references Error Prone annotations that are not packaged on Android.
-dontwarn com.google.errorprone.annotations.Immutable
