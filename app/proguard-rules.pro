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

# Glance widgets and ActionCallbacks are instantiated REFLECTIVELY by the
# framework — manifest receivers (MemoWidgetReceiver / TodayWidgetReceiver)
# `new MemoWidget()` etc. R8 only sees the receiver field assignment, not
# the actual class identity, so without this keep rule it strips MemoWidget
# / TodayWidget and the receiver crashes with ClassNotFoundException at
# install / first-fire time. PackageManager rejects the install when it
# can't resolve the receiver's referenced class.
#
# Also keep all of `dev.aria.memo.widget.**` because the receiver classes,
# the `Toasting…Action` callbacks (referenced via `actionRunCallback<T>()`
# generic-erased reflection), and the helpers (e.g. WidgetItemId) are all
# part of the same reflective surface.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class dev.aria.memo.widget.** { *; }

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

# ===== Manifest-declared components (Activity / Receiver / Application) =====
# Each of these is instantiated by the system via reflection on the class
# name in AndroidManifest.xml. Even though R8's default keep rules cover
# Activity / Application etc., explicitly listing our `dev.aria.memo` namespace
# makes the contract obvious and prevents silent strips when someone adds
# a new Receiver / Service.
-keep class dev.aria.memo.MemoApplication { *; }
-keep class dev.aria.memo.MainActivity { *; }
-keep class dev.aria.memo.EditActivity { *; }
-keep class * extends android.content.BroadcastReceiver { *; }

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

# ===== AndroidX Startup (P5) =====
# InitializationProvider scans manifest <meta-data> via reflection; keep the
# provider + Initializer subclasses so R8 doesn't strip them in release.
-keep class androidx.startup.InitializationProvider { *; }
-keep class * implements androidx.startup.Initializer { *; }
-keepnames class androidx.startup.R$string

# ===== Kotlin metadata (needed by serialization & reflection-light APIs) =====
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class kotlin.Metadata { *; }

# Keep source file / line numbers for readable stack traces in release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
