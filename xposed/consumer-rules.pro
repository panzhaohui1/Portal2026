-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn de.robv.android.xposed.**

# Xposed Entry Point
-keep class moe.fuqiuluo.xposed.FakeLocation { *; }

# Keep the entire xposed package for inter-module communication
-keep class moe.fuqiuluo.xposed.** { *; }

# Keep system refined members if any
-keep class dev.rikka.tools.refine.** { *; }

# Keep Bugly
-keep class com.tencent.bugly.** { *; }

# Keep JSON serialization
-keep class kotlinx.serialization.json.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
