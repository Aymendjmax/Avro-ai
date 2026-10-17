<div align="center">

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="avro-brand-kit/logo/png/avro-lockup-white.png">
    <source media="(prefers-color-scheme: light)" srcset="avro-brand-kit/logo/png/avro-lockup-black.png">
    <img src="avro-brand-kit/logo/png/avro-lockup-white.png" alt="Avro Logo" width="220" />
  </picture>

  <br />

  **تطبيق ذكاء اصطناعي مستقل مع بيئة لينكس مدمجة وتنفيذ مباشر دون وسطاء**

  <br />

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-black.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-black.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-Native-black.svg)](https://kotlinlang.org/)
  [![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-black.svg)](https://developer.android.com/jetpack/compose)

  <br />

  [ English ](README.md) · [ العربية ](README.ar.md) · [ 简体中文 ](README.zh.md) · [ 繁體中文 ](README.zh-Hant.md)

  <br />

  <img src="avro-brand-kit/cover/avro-cover-A-light-left.png" alt="واجهة تطبيق Avro" width="100%" />

</div>

---

## نظرة عامة

Avro هو تطبيق أندرويد محلي وبيئة تشغيل لوكلاء الذكاء الاصطناعي المستقلين. يتصل التطبيق مباشرة من جهازك بموفري النماذج باستخدام مفاتيحك الخاصة (BYOK - Bring Your Own Key)، دون المرور عبر أي خوادم وسيطة أو خدمات تتبع.

يتضمن التطبيق بيئة Alpine Linux معزولة (Sandbox) مدمجة بالكامل، مما يتيح تشغيل الأكواد البرمجية، وإدارة حزم النظام، وتنفيذ أدوات متسلسلة مباشرة على هاتفك.

---

## المبادئ المعمارية

- **الاتصال المباشر**: جميع طلبات الـ API تنطلق من الجهاز وتصل مباشرة إلى الخوادم الرسمية للمزودين. لا توجد خوادم وسيطة أو نقاط تجميع بيانات خارجية.
- **ساندبوكس لينكس مدمج (PRoot)**: تشغيل نظام Alpine Linux حقيقي دون الحاجة لصلاحيات الروت (Root). يدعم تثبيت الحزم عبر `apk` وتشغيل سكربتات بايثون، الشل، والبرمجيات المبنية.
- **محرك واجهات ثنائي الاتجاه**: مصمم بدعم أصلي للغات من اليمين إلى اليسار (RTL) ومن اليسار إلى اليمين (LTR)، مما يوفر محاذاة وتناسقاً بصرياً دقيقاً في العربية والإنجليزية.
- **السيادة على البيانات**: المحادثات، الأدوات، الذاكرة، والمفاتيح الحساسة تظل محفوظة ومشفرة محلياً على جهازك.

---

## القدرات والميزات

### الاتصال بالنماذج
- **دعم أكثر من 10 مزودين**: OpenAI, Anthropic Claude, Google Gemini, DeepSeek, Qwen / DashScope, Groq, Ollama, OpenRouter, OpenCode, وتشغيل Llama.cpp محلياً.
- **مهايئات مخصصة للبروتوكولات**: إمكانية تعديل عناوين URL للاتصال بأي خادم متوافق مع بروتوكولات OpenAI أو Gemini أو Anthropic.
- **التشغيل المحلي لنماذج GGUF**: تشغيل النماذج وتوليد التضمينات الشعاعية بدون إنترنت بالكامل عبر محرك llama.cpp المدمج.

### الأدوات والبروتوكولات المستقلة
- **بيئة ترمينال لينكس**: تنفيذ معزول مع إمكانية الوصول إلى الملفات وإدارة العمليات في الخلفية.
- **البحث في الويب**: استعلامات حية عبر DuckDuckGo و Brave Search مع دمج الاقتباسات بشكل فوري أثناء التوليد.
- **بروتوكول سياق النماذج (MCP)**: عميل مدمج للاتصال بخوادم أدوات MCP عبر SSE و HTTP.
- **الاسترجاع المعزز بالتوليد (RAG)**: بحث تشابهي دلالي عبر تاريخ المحادثات والذاكرة المحفوظة.
- **التنفيذ في الخلفية**: تشغيل الحلقات الطويلة ومهام الشل المجدولة عبر خدمات أندرويد الأمامية (Foreground Services).

### الواجهة وتجربة المستخدم
- **تصميم Material 3 أحادي اللون (Monochrome)**: واجهة عالية التباين تركز على وضوح الخطوط وتتبع لغة Avro-MN.
- **شجرة محادثات متفرعة**: تعديل وإعادة توليد الرسائل السابقة دون فقدان المسارات البديلة.
- **إدارة ميزانية السياق**: عداد فوري للرموز (Tokens) مع حدود قابلة للتخصيص (من 4K إلى أكثر من 1M رمز) وضغط ذكي وغير مدمر للسياق.

---

## هيكل المستودع

```text
├── app/                      # وحدة تطبيق أندرويد
│   ├── src/main/java/        # الكود المصدري بلغة كوتلن (معمارية نظيفة)
│   │   └── com/newoether/agora/
│   │       ├── api/          # عملاء موفري النماذج ومحللات البث
│   │       ├── automation/   # مهام الخلفية وعمال الشل
│   │       ├── data/         # المستودعات وقواعد بيانات Room و DataStore
│   │       ├── diagnostics/  # التشخيص ومراقبة الأداء المحلي
│   │       ├── mcp/          # تطبيق بروتوكول MCP
│   │       ├── model/        # كيانات النطاق ونماذج شجرة المحادثة
│   │       ├── remote/       # الاتصال بالخوادم البعيدة وإقران الأجهزة
│   │       ├── sandbox/      # مدير ساندبوكس Alpine Linux ونظام الملفات
│   │       ├── service/      # خدمات التشغيل في الخلفية
│   │       ├── tool/         # أدوات الوكيل (البحث، الذاكرة، الملفات)
│   │       ├── ui/           # واجهات Jetpack Compose والشاشات
│   │       ├── util/         # أدوات التشفير والتحليل والتنسيق
│   │       ├── viewmodel/    # نماذج العرض (StateFlow ViewModels)
│   │       └── webui/        # خادم WebUI محلي مدمج
│   ├── src/main/res/         # الموارد المرئية، الأيقونات، والترجمات
│   └── src/test/             # حزمة اختبارات الوحدة و Robolectric
├── avro-brand-kit/           # أصول الهوية البصرية، الرموز، والأدلة
├── docs/                     # التوثيق الشامل (العربية، الإنجليزية، الصينية)
├── development/              # مواصفات الهندسة المعمارية والـ RFCs
├── scripts/                  # سكربتات الأتمتة والبناء
├── server/                   # خدمات المزامنة الاختيارية
├── thirdparty/               # مكتبات PRoot و talloc و llama.cpp
└── build.gradle.kts          # إعدادات بناء Gradle الأساسية
```

---

## تعليمات البناء والتشغيل

### المتطلبات
- JDK 17 أو JDK 21
- حزمة Android SDK (متوافقة مع API 36 / Android 16)
- نظام Gradle 9 فأحدث

### أوامر البناء

```bash
# استنساخ المستودع
git clone https://github.com/fifikoridek2-boop/Avro.git
cd Avro

# بناء نسخة تصحيح الأخطاء (Debug APK)
gradle assembleDebug

# تشغيل اختبارات الوحدة المحلية
gradle :app:testDebugUnitTest
```

---

## التوثيق

تتوفر الأدلة الكاملة داخل مجلد `docs`:
- [المعمارية التقنية](ARCHITECTURE.md)
- [دليل التوثيق العربي](docs/ar/index.md)
- [دليل المستخدم الإنجليزي](docs/en/index.md)
- [دليل الهوية البصرية](avro-brand-kit/docs/BRAND-GUIDE.md)
- [سياسة الخصوصية](PRIVACY.md)

---

## الترخيص والنسب

يخضع مشروع Avro لترخيص **GNU General Public License v3.0 (GPL-3.0)** (راجع ملف LICENSE).  
مشتق من مشروع Agora مفتوح المصدر ومطور بواسطة Newo Ether، مع هوية بصرية مخصصة، دعم كامل للعربية، وتطويرات هندسية مستقلة.
