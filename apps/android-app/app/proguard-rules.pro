# ─── SkoLab ProGuard / R8 Rules ───────────────────────────────────────────────
# Tightly scoped to let R8 tree-shake everything we don't explicitly need.

# ── Kotlin ─────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# ── Kotlin Serialization ───────────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class ** implements kotlinx.serialization.KSerializer {
    static ** Companion;
    static ** $serializer;
}
-dontwarn kotlinx.serialization.**

# ── SkoLab data models (used in JSON deserialization) ─────────────────────────
-keep class com.company.skolab.model.** { *; }
-keep class com.company.skolab.network.** { *; }
-keep class com.company.skolab.data.** { *; }

# ── Compose: stability annotations (required for @Stable/@Immutable) ──────────
-keep @androidx.compose.runtime.Stable class * { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }
-dontwarn androidx.compose.**

# ── Ktor: keep only client + serialization; let R8 drop unused engines ─────────
-keep class io.ktor.client.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.serialization.** { *; }
-dontwarn io.ktor.**

# ── OkHttp: keep reflection targets only; let R8 strip the rest ────────────────
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Firebase: keep only what is used; R8 strips the rest ──────────────────────
-keep class com.google.firebase.FirebaseApp { *; }
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.firestore.** { *; }
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Crashlytics: preserve stack traces ────────────────────────────────────────
-keepattributes SourceFile, LineNumberTable
-keep public class * extends java.lang.Exception
-renamesourcefileattribute SourceFile

# ── AndroidX DataStore ─────────────────────────────────────────────────────────
-keepnames class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── AndroidX Security Crypto ───────────────────────────────────────────────────
-keepnames class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Coroutines ─────────────────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Enums ──────────────────────────────────────────────────────────────────────
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

# ── Strip verbose logging in release builds (saves ~50KB) ─────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
