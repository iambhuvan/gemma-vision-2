# Keep Gemma 4 tool-call data classes
-keep class org.cmu.gemmavision2.tools.** { *; }
-keep class org.cmu.gemmavision2.inference.** { *; }

# Moshi
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }
-keep class **JsonAdapter { *; }

# Picovoice / Porcupine native bindings
-keep class ai.picovoice.** { *; }

# MediaPipe native
-keep class com.google.mediapipe.** { *; }

# Google AI Edge AICore (Dev Preview — keep public API stable)
-keep class com.google.ai.edge.aicore.** { *; }
-keep class com.google.mlkit.genai.prompt.** { *; }
