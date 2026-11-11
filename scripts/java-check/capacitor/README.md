# Capacitor API stubs for the offline Java check

`scripts/check-android-java.sh` compiles `android/app/src/main/java` on a machine with no Android
SDK, no Google Maven and no full JDK. Capacitor's own sources cannot be compiled there (they pull
in AndroidX WebKit, Fragment, Cordova and Java 15 syntax), so instead these files declare the
exact slice of Capacitor 8's API that the port calls.

The signatures are copied from
`node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor`. They are not a
reimplementation: every body returns a default and nothing here ships in the APK.
`check-android-java.sh` only uses this directory when the real Capacitor sources fail to compile
(which they always do in this sandbox), so CI's Gradle build remains the authority.
