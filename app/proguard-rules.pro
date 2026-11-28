# Правила R8 для Neon Pulse (release). Библиотеки AndroidX (Compose, Health Connect, WorkManager) приносят свои правила сами.

# Мост страницы Neon (WebView) → приложение: методы с @JavascriptInterface вызываются из JS по имени
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Вход через Google (Credential Manager): провайдер Play Services создаётся по имени класса
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Фоновая отправка: WorkManager создаёт Worker по имени класса
-keep class uz.neonpulse.app.SyncWorker { <init>(...); }

# Номера строк в отчётах о сбоях
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
