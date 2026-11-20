# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.uposum.romstore.**$$serializer { *; }
-keepclassmembers class io.github.uposum.romstore.** { *** Companion; }
-keepclasseswithmembers class io.github.uposum.romstore.** { kotlinx.serialization.KSerializer serializer(...); }

# Archive readers reference optional codecs they never need here.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
-dontwarn org.slf4j.**
-dontwarn com.github.junrar.**
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-keep class com.github.junrar.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
