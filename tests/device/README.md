# Android locale verification

This optional test APK checks every TV string through Android resource selection
for en-US, zh-CN, zh-TW, zh-HK, zh-Hant-HK, ja-JP and the English fallback for fr-FR.
It is not included in the app or Release APK.

Build with `build.ps1` in this directory using JAVA_HOME, ANDROID_HOME and
TWS_TV_KEYSTORE_PASSWORD for the project's local signing key.
Install the resulting temporary APK and run:

```powershell
adb shell am instrument -w se.zepiwolf.tws.tv.localetest/.LocaleTest
```

An optional `-e systemLocale en-US` changes the actual device language for a live
configuration-change test. Only use it on an authorized test device. It requires:

```powershell
adb shell pm grant se.zepiwolf.tws.tv.localetest android.permission.CHANGE_CONFIGURATION
adb shell appops set se.zepiwolf.tws.tv.localetest WRITE_SETTINGS allow
```

Always restore the device's original locale with the same parameter afterward,
then uninstall `se.zepiwolf.tws.tv.localetest`. These extra permissions belong only
to the temporary test package, not the TV app.
