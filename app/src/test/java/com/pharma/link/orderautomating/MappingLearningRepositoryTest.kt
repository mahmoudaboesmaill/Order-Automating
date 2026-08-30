package com.pharma.link.orderautomating

import org.junit.Assert.assertEquals
import org.junit.Test

class MappingLearningRepositoryTest {
    @Test
    fun confirmedCorrectionBuildsTheSameCodeForBothStores() {
        val item = PharmacyItem(
            itmCode = "EP-500-XR",
            nameAr = "تراليبسي 500 إكس آر",
            nameEn = "Trilepsy 500 XR",
            barcode = ""
        )

        val write = buildMappingWrite(" 29 ", "  تراليبسي 500 XR ", item, usageCount = 4)

        assertEquals("29", write.smartMapping.supplierCode)
        assertEquals("تراليبسي 500 xr", write.smartMapping.invoiceName)
        assertEquals("EP-500-XR", write.smartMapping.itmCode)
        assertEquals(write.smartMapping.supplierCode, write.correction.supplierCode)
        assertEquals(write.smartMapping.invoiceName, write.correction.ocrRawText)
        assertEquals(write.smartMapping.itmCode, write.correction.correctedItmCode)
        assertEquals(4, write.correction.usageCount)
    }

    @Test
    fun trainingNamesAreDeduplicatedAfterArabicNormalization() {
        val names = deduplicateTrainingNames(
            listOf(" تريالبسي 500 ", "تريالبسي   500", "أوجمنتين", "اوجمنتين", "")
        )

        assertEquals(listOf("تريالبسي 500", "أوجمنتين"), names)
    }
}
