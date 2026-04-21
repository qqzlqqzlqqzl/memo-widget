# ===== kotlinx.serialization =====
# Keep generated serializers for all @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep the Companion object of @Serializable classes so that
# the generated $serializer() stays reachable.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the generated companion serializer classes (e.g. `Foo$$serializer`).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
}
-keepclasseswithmembers class **$$serializer { *; }

# Keep all of our DTOs (they're small and minified names break the API contract).
-keep class dev.aria.memo.data.** { *; }

# ===== Ktor =====
# Ktor uses reflection in a few places; keep its classes.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ===== Glance =====
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# ===== Kotlin metadata (needed by serialization & reflection-light APIs) =====
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class kotlin.Metadata { *; }
