package com.pharma.link.orderautomating

object ArabicNormalizer {

    fun normalize(text: String): String {
        return text
            .trim()
            .lowercase()
            // توحيد الألف بكل أشكالها فقط
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            // توحيد الياء
            .replace('ى', 'ي')
            // توحيد التاء المربوطة
            .replace('ة', 'ه')
            // حذف التشكيل (الحركات فقط — الأرقام لا تتأثر)
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            // حذف التطويل
            .replace('ـ', ' ')
            // تنظيف المسافات الزائدة
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // للتحقق من أن رقمين متطابقان في الاسم (حماية إضافية)
    fun extractNumbers(text: String): List<String> {
        return Regex("\\d+").findAll(text).map { it.value }.toList()
    }

    // مطابقة آمنة: تتحقق من الحروف والأرقام معاً
    fun safeMatch(ocrName: String, dbName: String): Boolean {
        val normalizedOcr = normalize(ocrName)
        val normalizedDb  = normalize(dbName)
        if (normalizedOcr != normalizedDb) return false
        // تحقق إضافي: الأرقام لازم تكون متطابقة بالظبط
        return extractNumbers(ocrName) == extractNumbers(dbName)
    }
}
