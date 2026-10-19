package com.newoether.agora.util

/**
 * Avro Endpoints & External Links Configuration
 *
 * All external endpoints, API URLs, and repository links for Avro are centralized
 * here. Connected directly to the live Avro Web Platform on Vercel.
 *
 * جميع الروابط ونقاط النهاية الخارجية الخاصة بتطبيق Avro مجمعة هنا بشكل مركزي،
 * ومربوطة مباشرة بمنصة الويب والسيرفرات السحابية الرسمية.
 */
object AvroEndpoints {

    /**
     * Crash Reporting API endpoint (نقطة إرسال تقارير الأعطال)
     * Live Vercel serverless endpoint with Telegram Admin alerts.
     */
    const val CRASH_REPORT_ENDPOINT = "https://avro-app.vercel.app/api/crash"

    /**
     * In-App Rating & Feedback API endpoint (نقطة إرسال التقييمات والملاحظات)
     * Live Vercel serverless endpoint with Telegram Admin alerts.
     */
    const val RATING_API_ENDPOINT = "https://avro-app.vercel.app/api/rating"

    /**
     * Official Website URL (الموقع الإلكتروني الرسمي)
     */
    const val WEBSITE_URL = "https://avro-app.vercel.app"
    const val WEBSITE_DISPLAY = "avro-app.vercel.app"

    /**
     * GitHub Repository URL (مستودع الكود على جيت هب)
     */
    const val GITHUB_REPO_URL = "https://github.com/fifikoridek2-boop/Avro"

    /**
     * GitHub Issues Tracker (متتبع المشاكل والاقتراحات)
     */
    const val GITHUB_ISSUES_URL = "https://github.com/fifikoridek2-boop/Avro/issues"

    /**
     * GitHub Pull Requests / Contribution (رابط المساهمة)
     */
    const val GITHUB_CONTRIBUTE_URL = "https://github.com/fifikoridek2-boop/Avro/pulls"

    /**
     * Privacy Policy URL (رابط سياسة الخصوصية)
     */
    const val PRIVACY_POLICY_URL = "https://avro-app.vercel.app/docs/en/privacy"

    /**
     * Documentation URL (رابط بوابة التوثيق متعددة اللغات)
     */
    const val DOCUMENTATION_URL = "https://avro-app.vercel.app/docs"

    /**
     * GitHub Releases API for Checking Updates (رابط فحص التحديثات الرسمية)
     */
    const val UPDATE_CHECK_API_URL = "https://api.github.com/repos/fifikoridek2-boop/Avro/releases/latest"
}
