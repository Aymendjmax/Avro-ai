package com.newoether.agora.util

/**
 * Avro Endpoints & External Links Configuration
 *
 * All external endpoints, API URLs, and repository links for Avro are centralized
 * here. You can easily customize or update these URLs anytime directly in this file.
 *
 * جميع الروابط ونقاط النهاية الخارجية الخاصة بتطبيق Avro مجمعة هنا بشكل مركزي،
 * يمكنك تعديل أي رابط أو خادم في أي وقت بسهولة من خلال هذا الملف.
 */
object AvroEndpoints {

    /**
     * Crash Reporting API endpoint (نقطة إرسال تقارير الأعطال)
     * Replace with your own server URL when ready (e.g. "https://api.yourdomain.com/crash").
     * Currently set to a placeholder mock URL.
     */
    const val CRASH_REPORT_ENDPOINT = "https://api.avro.app/crash"

    /**
     * In-App Rating & Feedback API endpoint (نقطة إرسال التقييمات والملاحظات)
     * Replace with your own server URL when ready (e.g. "https://api.yourdomain.com/rating").
     * Currently set to a placeholder mock URL.
     */
    const val RATING_API_ENDPOINT = "https://api.avro.app/rating"

    /**
     * Official Website URL (الموقع الإلكتروني الرسمي)
     */
    const val WEBSITE_URL = "https://avro.app"
    const val WEBSITE_DISPLAY = "avro.app"

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
    const val PRIVACY_POLICY_URL = "https://github.com/fifikoridek2-boop/Avro/blob/main/PRIVACY.md"

    /**
     * Documentation URL (رابط دليل التوثيق)
     */
    const val DOCUMENTATION_URL = "https://github.com/fifikoridek2-boop/Avro/tree/main/docs"

    /**
     * GitHub Releases API for Checking Updates (رابط فحص التحديثات الرسمية)
     */
    const val UPDATE_CHECK_API_URL = "https://api.github.com/repos/fifikoridek2-boop/Avro/releases/latest"
}
