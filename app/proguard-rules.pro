# kotlinx.serialization keeps the generated serializers reachable.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.pawse.scoring.**$$serializer { *; }
-keepclassmembers class app.pawse.scoring.** {
    *** Companion;
}

# SQLCipher loads native code by reflection.
-keep class net.zetetic.database.** { *; }

# Health Connect record classes are resolved by KClass at read time.
-keep class androidx.health.connect.client.records.** { *; }
