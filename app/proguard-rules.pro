# OkHttp / Okio ship their own consumer rules; silence optional platform warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Keep our model classes readable in crash traces.
-keepattributes SourceFile,LineNumberTable
