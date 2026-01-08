-allowaccessmodification
-repackageclasses 'o'

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}

-keepattributes *Annotation*,Signature

-keepclassmembers class com.ebagesprpe.gyselbevsb.data.** {
    <fields>;
}

-keep class com.ebagesprpe.gyselbevsb.core.PhaseAnalyzer$AnalysisResult { *; }