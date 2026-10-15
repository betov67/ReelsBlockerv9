# ReelsBlocker v9 ProGuard / R8 rules.
#
# All of our components (the AccessibilityService, the foreground service and
# the two BroadcastReceivers) are declared in AndroidManifest.xml, so R8's
# default Android rules already keep them and their zero-argument
# constructors automatically — no extra -keep rules are required for normal
# operation.
#
# Keep line-number info in stack traces for crash logs pulled off-device.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
