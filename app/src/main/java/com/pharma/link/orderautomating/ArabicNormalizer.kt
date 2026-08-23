package com.pharma.link.orderautomating

object ArabicNormalizer {

    // قائمة الأشكال الصيدلانية الشائعة (عربي وإنجليزي)
    private val DOSAGE_FORMS = listOf(
        // عربي
        "اقراص", "قرص", "شراب", "معلق", "كبسول", "كبسولات", "كبسوله",
        "كريم", "مرهم", "امبول", "امبولات", "امبولة", "فيل", "فيال",
        "نقط", "بخاخ", "سبراي", "اسبراي", "جيل", "جل", "لوشن",
        "اكياس", "كيس", "تحاميل", "لبوس", "قطرة", "قطره", "فوار",
        "شامبو", "صابون", "بلسم", "محلول", "غسول", "معجون",
        // إنجليزي
        "tab", "tabs", "tablets", "tablet", "syr", "syrup",
        "susp", "suspension", "cap", "caps", "capsules", "capsule",
        "crm", "cream", "oint", "ointment", "amp", "ampoule", "ampoules",
        "vial", "vials", "drops", "drop", "spray", "gel", "lotion",
        "sachet", "sachets", "eff", "supp", "suppositories",
        "shampoo", "soap", "solution", "sol", "mouthwash", "paste"
    )

    fun normalize(text: String): String {
        return text
            .trim()
            .lowercase()
            // توحيد الألف بكل أشكالها
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            // توحيد الياء والهمزات على نبرة/ياء
            .replace('ى', 'ي')
            .replace('ئ', 'ي')
            .replace('ؤ', 'و')
            // توحيد التاء المربوطة
            .replace('ة', 'ه')
            // إزالة التشكيل (الحركات)
            .replace(Regex("[\u064B-\u065F\u0670]"), "")
            // استبدال الرموز وعلامات الترقيم بمسافات
            .replace(Regex("[-+/*().,;:\\[\\]{}|_\u0640]"), " ")
            // تنظيف المسافات الزائدة
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // تجريد الأشكال الصيدلانية للحصول على اسم العقار والتركيز الأساسي
    fun stripDosageForms(text: String): String {
        val normalized = normalize(text)
        val words = normalized.split(" ")
        val filtered = words.filter { word ->
            word.length > 1 && !DOSAGE_FORMS.contains(word)
        }
        return if (filtered.isNotEmpty()) filtered.joinToString(" ") else normalized
    }

    // استخراج الأرقام والتراكيز من الاسم (مثل: 500, 1000, 20)
    fun extractNumbers(text: String): List<String> {
        return Regex("\\d+").findAll(text).map { it.value }.toList()
    }

    // مطابقة آمنة: تتحقق من تطابق الاسم والأرقام معاً
    fun safeMatch(ocrName: String, dbName: String): Boolean {
        val normalizedOcr = normalize(ocrName)
        val normalizedDb  = normalize(dbName)
        if (normalizedOcr == normalizedDb) return true

        val strippedOcr = stripDosageForms(ocrName)
        val strippedDb  = stripDosageForms(dbName)
        if (strippedOcr.isNotEmpty() && strippedOcr == strippedDb) {
            return extractNumbers(ocrName) == extractNumbers(dbName)
        }
        return false
    }
}

