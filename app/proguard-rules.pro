# kotlinx.serialization keeps its serializers via generated companions; R8 needs
# to be told not to strip them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.kiro.** {
    *** Companion;
}
-keepclasseswithmembers class dev.kiro.** {
    kotlinx.serialization.KSerializer serializer(...);
}
