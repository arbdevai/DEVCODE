# ProGuard rules for DEVCODE Android Application

# Keep Compose runtime and effect types
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }

# Keep ViewModel and State
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Material3 types
-keep class androidx.material3.** { *; }
-keep interface androidx.material3.** { *; }

# Keep serializable objects
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep generic signatures
-keepattributes Signature
-keepattributes *Annotation*

# Custom application class
-keep class com.devcode.** { *; }