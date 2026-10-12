# Regras ProGuard/R8.
# O template vai com minify desligado no release. Se o seu port ativar R8,
# mantenha as regras abaixo para preservar o contrato do ViewModel.
-keepclassmembers class com.deivid22srk.restuff.viewmodel.** { <init>(...); }

# libmodarchive.so calls back into these through JNI.
-keep class com.deivid22srk.restuff.mods.ArchiveExtractor { *; }
-keep interface com.deivid22srk.restuff.mods.ArchiveExtractor$Listener { *; }
