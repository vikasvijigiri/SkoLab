# ─── SkoLab ProGuard / R8 Rules ───────────────────────────────────────────────

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ── Kotlin Serialization ───────────────────────────────────────────────────────
# Keep all @Serializable data classes so kotlinx.serialization can reflect on them
-keepattributes RuntimeVisibleAnnotations
-keep @kotlinx.serialization.Serializable class ** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Keep generated serializer companion objects (named $serializer)
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class ** implements kotlinx.serialization.KSerializer {
    static ** Companion;
    static ** $serializer;
}

# ── SkoLab data models (used in JSON deserialization) ─────────────────────────
-keep class com.company.skolab.model.** { *; }
-keep class com.company.skolab.network.** { *; }

# ── Ktor ───────────────────────────────────────────────────────────────────────
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Firebase ───────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ── Firebase Crashlytics ───────────────────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }

# ── AndroidX DataStore ─────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── AndroidX Security Crypto (EncryptedSharedPreferences) ─────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Jetpack Compose ────────────────────────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Coroutines ─────────────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Enums — preserve name() and values() which kotlinx.serialization uses ──────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public final java.lang.String name();
    public final int ordinal();
}

# ── Markwon (Markdown renderer) ────────────────────────────────────────────────
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ── Google Sign-In / Credential Manager ───────────────────────────────────────
-keep class com.google.android.libraries.identity.** { *; }
-dontwarn com.google.android.libraries.identity.**

# ── R8 source line preservation (required for crash stack traces) ──────────────
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
