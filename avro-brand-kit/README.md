# Avro Brand Kit v1.0

## المحتويات
```
Agent.md                      تعريف المشروع للوكلاء الذكيين (إنجليزي)
docs/BRAND-GUIDE.md           دليل الهوية البصرية الكامل
docs/PHASE-1-GUIDE.md         تنفيذ المرحلة الأولى (فيديوهات + ثيم)
logo/svg, logo/png            الشعار والكلمة وأيقونات التطبيق
cover/                        غلاف 1280×720 بنسختين (PNG)
video/                        الفيديوهات بأسمائنا (داكن/فاتح)
android-res-raw/              نفس الفيديوهات بأسماء Agora الجاهزة لـ res/raw
tokens/avro-tokens.json       الألوان وثوابت الحركة
source/                       مصادر الأنيميشن (HTML) وصفحة معاينة الألوان
```

## الشعار (`logo/`)
| الملف | الاستخدام |
|---|---|
| `avro-mark-white-on-black` / `avro-mark-black-on-white` | الشعار مع خلفية |
| `avro-mark-white` / `avro-mark-black` | شفاف |
| `avro-mark-solid-*` | بدون فتحة، للأحجام الصغيرة (<32px) |
| `avro-lockup-*` | الشعار مع الكلمة `Avro` (الكلمة مرسومة كمسارات، لا تحتاج خطاً) |
| `avro-app-icon-dark/light` | أيقونة التطبيق (PNG بحجمين: 1024 و512) |

PNG: الشعار 1024px، والـlockup عرض 2048px.

## الغلاف (`cover/`)
| الملف | الوصف |
|---|---|
| `avro-cover-A-light-left.png` | خلفية بيضاء، ولوحة التطبيق داكنة |
| `avro-cover-B-dark-left.png` | المعكوسة: خلفية سوداء، ولوحة فاتحة |

1280×720. الكلمة الكبيرة هي الـlockup المعتمد وتنقسم لونياً عند حافة اللوحة، والأقراص تعرض اسم Avro. الأحرف من Outfit الرسمي.

## الفيديوهات
| في `video/` | في `android-res-raw/` | السلوك |
|---|---|---|
| `avro-intro-loop-dark/light.mp4` | `welcome_video_1(.mp4/_light.mp4)` | **يتكرر** |
| `avro-providers-once-dark/light.mp4` | `welcome_video_2(.mp4/_light.mp4)` | **مرة واحدة** ويثبت |

1024×1024 · 60fps · H.264. النسخة الفاتحة هي عكس ألوان الداكنة.

## إعادة التصدير
المصادر في `source/` (صفحات HTML تحتوي الأنيميشن كاملاً، دالة `draw(t)`). صُدّرت الفيديوهات بتصوير الإطارات (60 إطاراً/ثانية) من متصفح Chromium بدون واجهة ثم ترميزها بـ ffmpeg (H.264، crf 12)، والفاتح بعكس الألوان.

## تنبيهات
- شعارات OpenAI وClaude وGemini داخل فيديو المزودين علامات تجارية لأصحابها (الأيقونات من Lobe Icons، رخصة MIT).
- افحص تعارض الاسم "Avro" قبل الإطلاق.
- Agora مشروع GPL-3.0، راجع `Agent.md`.
