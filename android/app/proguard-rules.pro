# Meter keeps no reflection-heavy code; these rules only guard the pieces that
# the framework instantiates by name (services, receivers).
-keep class com.meter.app.service.** { *; }
-keep class com.meter.app.MeterApp { *; }
-dontwarn org.jetbrains.annotations.**
