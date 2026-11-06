# Accessibility/tile services and activities are referenced from the manifest (kept by AAPT rules).
-keepclassmembers class * extends android.app.Activity { public void *(android.view.View); }
