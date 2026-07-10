# Stepcast release rules. Room, Media3, Compose, OkHttp and Coil all ship
# consumer rules.

# Glance widget action callbacks are instantiated reflectively when a
# widget button is tapped (ActionCallbackBroadcastReceiver does
# Class.forName().newInstance() on the class name baked into the
# PendingIntent). R8's optimize profile strips the never-referenced
# no-arg constructors — and can rename the classes out from under
# already-placed widgets — which silently kills every widget button in
# release builds.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
