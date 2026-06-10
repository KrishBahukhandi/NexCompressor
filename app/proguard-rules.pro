# NexCompress ProGuard / R8 rules.

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Kotlin coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- Google Mobile Ads (AdMob) ---
# The SDK ships its own consumer rules; these guard against over-stripping.
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# Keep model/entity field names used reflectively by Room.
-keepclassmembers class com.nexcompress.app.data.local.** { *; }

# --- PDFBox-Android (tom_roush) ---
# The PDF engine loads fonts/glyph resources reflectively; keep it intact.
-keep class com.tom_roush.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**
