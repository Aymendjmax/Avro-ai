# المرحلة الأولى — الفيديوهات والثيم الافتراضي
> للمنفّذ (إنسان أو وكيل ذكاء اصطناعي). مرجع: `Agent.md`. الملفات التي ستُنسخ موجودة في `android-res-raw/`.

## الهدف
1. استبدال فيديوهي الصفحتين الأولى والثانية في شاشة الترحيب بفيديوهاتنا (الداكنة والفاتحة).
2. **الفيديو الأول (تركيب الشعار) يتكرر. الفيديو الثاني (المزودون) لا يتكرر** ويثبت على آخر إطار.
3. إضافة نظام الألوان الجديد **Avro-MN** وجعله الافتراضي.

> ملاحظة: الملفات **MP4** (فيديو)، وليست MP3.

## 1) استبدال الفيديوهات
انسخ الملفات الأربعة من `android-res-raw/` إلى `app/src/main/res/raw/` واستبدل الموجودة:

| الملف | الصفحة | المحتوى |
|---|---|---|
| `welcome_video_1.mp4` / `_light` | الأولى | تركيب الشعار (حلقة) |
| `welcome_video_2.mp4` / `_light` | الثانية | المزودون (مرة واحدة) |

- **لا تلمس** `welcome_video_3` و`welcome_video_4`؛ تبقى من Agora الآن (رسوم واجهة عامة بلا علامة). نستبدلها في المرحلة الثالثة.
- المواصفات: 1024×1024، 60fps، H.264. الأول 8 ث، والثاني 11 ث (فيديوهات Agora الأصلية 1 و2 مدتها 6.5 ث).
- أسماء ملفات `res/raw` يجب أن تبقى بأحرف صغيرة وشرطة سفلية، كما هي.

## 2) قواعد التكرار (`ui/onboarding/WelcomeScreen.kt`)
الوضع الحالي: كل المشغلات `Player.REPEAT_MODE_ALL`. وتبدأ بعد أول زيارة للصفحة فقط (`visitedPages` ثم `playWhenReady = true`).

أضف ثابتاً:
```kotlin
// Page whose video must play once and hold the last frame (providers animation).
private const val PAGE_PROVIDERS_VIDEO = 1
```
(لا تخلطه مع `PAGE_PROVIDER = 2` الموجود، فهذا لصفحة اختيار المزوّد.)

وغيّر إنشاء المشغلات من `pages.map` إلى `mapIndexed`:
```kotlin
val players = remember(isDarkTheme) {
    pages.mapIndexed { index, page ->
        val resId = resolveVideoRes(isDarkTheme, page.darkVideoResId, page.lightVideoResId)
        resId?.let {
            val uri = "android.resource://${context.packageName}/$it"
            ExoPlayer.Builder(context).build().apply {
                repeatMode = if (index == PAGE_PROVIDERS_VIDEO) Player.REPEAT_MODE_OFF
                             else Player.REPEAT_MODE_ALL
                playWhenReady = false
                setMediaItem(MediaItem.fromUri(uri)); prepare()
            }
        }
    }
}
```
**السلوك المتوقع:** فيديو الصفحة 1 يتكرر. فيديو الصفحة 2 يلعب مرة عند أول زيارة ويبقى على الإطار الأخير، ولا يعيد اللعب عند الرجوع للصفحة.

**للتحقق على الجهاز:** هل يحتفظ `PlayerView` بآخر إطار عند `STATE_ENDED`؟ (السلوك الافتراضي نعم.) إن اختفى الإطار أضف `keepContentOnPlayerReset = true`.

**تحسينان اختياريان (سلوك موروث من Agora):**
- عند تبديل الثيم تُعاد إنشاء المشغلات بـ`playWhenReady=false` والصفحات المزارة لا تعيد التشغيل. للفيديو الثاني يمكن عندها القفز إلى آخر إطار (`seekTo`) ليظهر الشعار المكتمل.
- عند تقليل الحركة (`allowContinuousMotion == false`) المشغلات تُوقَف. الأفضل أن يُعرض إطار الفيديو الثاني الأخير.

## 3) نظام الألوان Avro-MN
**الاسم المقترح:** كود `AVRO_MN` (MN = Monochrome)، واسم العرض "Avro Mono". والأطقم القادمة تتبع النمط `Avro-XX`.

### أ) `ui/theme/Color.kt`
```kotlin
import com.materialkolor.scheme.SchemeMonochrome   // verify it exists in materialkolor 4.1.1

enum class ColorSchemePreset { AVRO_MN, MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN }

private val seedColors = mapOf(
    ColorSchemePreset.AVRO_MN to 0xFF808080,   // ignored by Monochrome, but keeps seedColors[preset]!! safe
    // ... existing entries unchanged
)

fun colorSchemeForPreset(preset: ColorSchemePreset, style: SchemeStyle = SchemeStyle.TONAL_SPOT, isDark: Boolean = false): ColorScheme {
    val seedArgb = seedColors[preset]!!.toInt()
    val hct = Hct.fromInt(seedArgb)
    val scheme: DynamicScheme = if (preset == ColorSchemePreset.AVRO_MN) {
        SchemeMonochrome(hct, isDark, 0.0)      // pure gray, style is irrelevant
    } else when (style) {
        // ... existing branches unchanged
    }
    return scheme.toColorScheme()
}
```
**مهم:** بدون الإدخال في `seedColors` يحدث **crash** بسبب `!!`. وضع `AVRO_MN` **أول** التعداد ليظهر أول القائمة.

### ب) القيمة الافتراضية (أربعة أماكن)
| الملف | التغيير |
|---|---|
| `data/SettingsPreferenceSchema.kt` | `DEFAULT_COLOR_SCHEME = "AVRO_MN"` |
| `ui/theme/Theme.kt` (سطر ~78) | `colorSchemePreset: ColorSchemePreset = ColorSchemePreset.AVRO_MN` |
| `ui/settings/SettingsAppearancePage.kt` (سطر ~133) | الرجوع الاحتياطي داخل `catch` إلى `AVRO_MN` |
| `MainActivity.kt` (سطر ~138) | الرجوع الاحتياطي داخل `catch` إلى `AVRO_MN` |

أرقام الأسطر من نسخة المستودع التي فُحصت، وقد تختلف قليلاً. ابحث بـ`ColorSchemePreset.FOREST`.

### ج) اسم الطقم في الإعدادات
`ui/settings/SettingsAppearancePresentation.kt`، داخل الـ`when` (شامل، فنسيانه خطأ ترجمة):
```kotlin
ColorSchemePreset.AVRO_MN -> stringResource(R.string.color_scheme_avro_mn)
```
وفي `res/values/strings.xml`:
```xml
<string name="color_scheme_avro_mn" translatable="false">Avro Mono</string>
```
(`translatable="false"` يمنع تحذير الترجمة الناقصة في باقي اللغات.)

### د) صف "نمط التوليد"
في `SettingsAppearancePage.kt` (حلقة `SchemeStyle.entries.forEach` قرابة السطر 345): عند اختيار `AVRO_MN` عطّل الصف أو أخفِه، لأن النمط لا يؤثر عليه.

### هـ) وضع AMOLED (اختياري ومنصوح به)
مفتاحه `AMOLED_ENABLED` في `SettingsPreferenceSchema.kt`. تحقق من قيمته الافتراضية في `SettingsManager`، وإن كانت `false` اجعلها `true` ليطابق الخلفية السوداء النقية للهوية.

### و) إن لم يوجد `SchemeMonochrome`
سيظهر ذلك كخطأ ترجمة (وليس عند التشغيل). الخيارات بالترتيب: (1) ابحث عن `Variant.MONOCHROME` أو `PaletteStyle.Monochrome` في إصدار المكتبة. (2) حدّث `materialKolor` في `gradle/libs.versions.toml`. (3) أنشئ `ColorScheme` يدوياً من `tokens/avro-tokens.json` (ملأ باقي الأدوار بنفس الخوارزمية).

## 4) قائمة التحقق
- [ ] `./gradlew assembleDebug` ينجح بلا تحذيرات جديدة.
- [ ] تثبيت نظيف (مسح البيانات): الثيم الافتراضي Avro Mono، رمادي نقي بلا صبغة.
- [ ] الوضع الداكن والفاتح، مع AMOLED مفعّل ومعطّل.
- [ ] قائمة الأطقم في الإعدادات تعرض "Avro Mono" أولاً، وبقية الأطقم تعمل.
- [ ] صف نمط التوليد معطّل عند اختيار Avro Mono.
- [ ] الصفحة 1 من الترحيب: فيديو الشعار يتكرر بدون قفزة عند الإعادة.
- [ ] الصفحة 2: فيديو المزودين يلعب مرة ويثبت على آخر إطار، ولا يعيد عند الرجوع.
- [ ] النسخ الفاتحة والداكنة للفيديوهات تظهر حسب الثيم.
- [ ] تقليل الحركة: لا انهيار، ويظهر إطار ثابت مقبول.
- [ ] لا مرجع لشعار Agora في الصفحتين 1 و2.

## 5) خارج هذه المرحلة
نصوص الترحيب (`strings.xml` فيها "Welcome to Agora" وترجماتها)، أيقونة التطبيق واسمه ومعرّف الحزمة، أصول README، الفيديوهان 3 و4. كلها في المرحلة الثانية والثالثة (`Agent.md` القسم 6).

## 6) اقتراح الإيداعات (commits)
1. `feat(onboarding): replace welcome videos 1 and 2 with Avro animations`
2. `feat(onboarding): loop video 1, play video 2 once`
3. `feat(theme): add AVRO_MN monochrome preset and make it default`
