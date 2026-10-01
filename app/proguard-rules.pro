# youtubedl-android 使用 Jackson 反射反序列化 VideoInfo / VideoFormat，不能混淆。
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
