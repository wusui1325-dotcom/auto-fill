# Manifest components (extra safety; AGP also keeps these).
-keep public class com.wusper.auto.fill.AutoFillApp { <init>(); }
-keep public class com.wusper.auto.fill.MainActivity { <init>(); }
-keep public class com.wusper.auto.fill.ConfirmActivity { <init>(); }
-keep public class com.wusper.auto.fill.TotpActivity { <init>(); }
-keep public class com.wusper.auto.fill.AddTotpActivity { <init>(); }
-keep public class com.wusper.auto.fill.ScanTotpQrActivity { <init>(); }
-keep public class com.wusper.auto.fill.OtpAccessibilityService { <init>(); }
-keep public class com.wusper.auto.fill.OtpNotificationListener { <init>(); }

-keep class com.wusper.auto.fill.BuildConfig { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
