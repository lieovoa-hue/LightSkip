-keep class com.lightskip.app.service.SkipAccessibilityService { *; }
-keepclassmembers class com.lightskip.app.data.** { *; }
-keep public class com.lightskip.app.ui.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
