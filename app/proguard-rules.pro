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
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ===== Glance =====
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# ===== Room (P4) =====
# Room generates `<Entity>_Impl` / `<Dao>_Impl` classes at build time and
# looks them up reflectively — must survive minification.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep class androidx.room.paging.** { *; }
-keep class androidx.room.* { *; }
-keepclassmembers @androidx.room.Entity class * {
    <fields>;
    <init>(...);
}
-keepclassmembers class *_Impl { *; }
-keep class dev.aria.memo.data.local.** { *; }

# ===== WorkManager (P4) =====
# ListenableWorker subclasses are instantiated reflectively by WorkManager.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ===== Navigation Compose (P4) =====
-keepnames class androidx.navigation.** { *; }

# ===== Tink / security-crypto (P4) =====
# EncryptedSharedPreferences relies on Tink proto parsers that use reflection.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ===== Kizitonwose Calendar =====
-keep class com.kizitonwose.calendar.** { *; }

# ===== Kotlin metadata (needed by serialization & reflection-light APIs) =====
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class kotlin.Metadata { *; }

# Keep source file / line numbers for readable stack traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
