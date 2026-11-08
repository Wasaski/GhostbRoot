# GhostbRoot release rules: keep server + API surface (invoked via reflection-free routing).
-keep class com.ghostbroot.app.server.** { *; }
-keep class com.ghostbroot.app.model.** { *; }
