# sherpa-onnx's native code reads the config classes' fields and builds GeneratedAudio by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# pdfbox-android refers to optional desktop and JPEG 2000 classes it never loads on Android.
-dontwarn com.gemalto.jp2.**
-dontwarn javax.xml.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.compress.**
