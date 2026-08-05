# PRODUCT_SPEC 15 — release hardening.
#
# Phase 0 ships no release signing config and no crash reporting, so the only rules here are the
# ones needed for the libraries in use. Media3, WorkManager and the download coordinator add their
# own rules in the phase that introduces them.

# Kotlinx Serialization keeps generated serializers reachable by reflection.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}

# Protobuf lite reflects over generated message classes.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Room's generated implementations are looked up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
