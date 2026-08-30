package com.pharma.link.orderautomating

/**
 * قواعد الحساب الثابتة للموردين. لا تعتمد على صياغة الـ OCR، لذلك تبقى قابلة
 * للاختبار والمراجعة حتى إن تغير شكل الفاتورة.
 */
data class ExtractedSupplierItem(
    val quantity: Double,
    val bonus: Double = 0.0,
    val unitPrice: Double = 0.0,
    val discountPercent: Double = 0.0,
    val lineTotal: Double = 0.0,
    val pharmacistPrice: Double = 0.0,
    val pharmacistMargin: Double = 0.0,
    val distributorMargin: Double = 0.0,
    val taxPerItem: Double = 0.0,
    val taxTotal: Double = 0.0,
    val suggestedSalePrice: Double = 0.0,
    val consumerPrice: Double = 0.0
)

data class CalculatedSupplierValues(
    val purchasePrice: Double,
    val taxPerItem: Double,
    val salePrice: Double,
    val shouldUpdateSalePrice: Boolean,
    val priceAlertKind: String,
    val consumerPrice: Double,
    val purchasePriceFromPharmacistColumn: Double = 0.0,
    val purchasePriceFromLineTotal: Double = 0.0,
    val purchasePriceMethodsMatch: Boolean = true,
    val pharmaValidationWarnings: List<String> = emptyList()
)

data class PharmaMarginRow(
    val matchKey: String,
    val quantity: Double,
    val lineTotal: Double,
    val taxPerItem: Double,
    val pharmacistMargin: Double
)

data class PharmaMarginRecovery(
    val margins: List<Double>,
    val reconciled: Boolean
)

object SupplierInvoiceRules {
    const val ALERT_SALE_PRICE = "sale_price"
    const val ALERT_PURCHASE_PRICE = "purchase_price"
    private const val PHARMA_COMPARISON_TOLERANCE = 0.50
    private const val PHARMA_COLUMN_COLLISION_TOLERANCE = 0.01

    /**
     * Recover faint Pharma margins without another OCR request. The invoice
     * total equals the printed row totals plus the fixed pharmacist margins.
     * We first propagate a readable margin to an identical repeated product,
     * then solve remaining groups using only margin values that were actually
     * read elsewhere on the same invoice. A value is applied only when there
     * is exactly one invoice-wide solution; ambiguous invoices stay unchanged
     * so the review screen can warn the user instead of inventing a number.
     */
    fun recoverPharmaMargins(
        invoiceTotal: Double,
        rows: List<PharmaMarginRow>
    ): PharmaMarginRecovery {
        if (invoiceTotal <= 0.0 || rows.isEmpty()) {
            return PharmaMarginRecovery(rows.map { it.pharmacistMargin }, false)
        }

        val margins = rows.map { row ->
            if (row.taxPerItem > 0.0) 0.0 else row.pharmacistMargin.coerceAtLeast(0.0)
        }.toMutableList()

        val groupedIndices = rows.indices.groupBy { index ->
            rows[index].matchKey.ifBlank { "__row_$index" }
        }
        groupedIndices.values.forEach { indices ->
            val readable = indices.map { margins[it] }.filter { it > 0.0 }.distinct()
            if (readable.size == 1) {
                indices.filter { rows[it].taxPerItem <= 0.0 }.forEach { margins[it] = readable.first() }
            }
        }

        val printedRowsTotal = rows.sumOf { it.lineTotal.coerceAtLeast(0.0) }
        val targetMarginTotal = invoiceTotal - printedRowsTotal
        if (targetMarginTotal <= 0.0) {
            return PharmaMarginRecovery(margins, false)
        }

        val candidates = margins.filter { it > 0.0 }.distinct().sorted()
        if (candidates.isEmpty()) return PharmaMarginRecovery(margins, false)

        val missingGroups = groupedIndices.values.filter { indices ->
            indices.any { rows[it].taxPerItem <= 0.0 } &&
                indices.filter { rows[it].taxPerItem <= 0.0 }.all { margins[it] <= 0.0 }
        }
        if (missingGroups.isEmpty() || missingGroups.size > 12) {
            val current = rows.indices.sumOf { margins[it] * rows[it].quantity.coerceAtLeast(0.0) }
            return PharmaMarginRecovery(margins, kotlin.math.abs(current - targetMarginTotal) <= 0.10)
        }

        val knownTotal = rows.indices.sumOf { index ->
            margins[index] * rows[index].quantity.coerceAtLeast(0.0)
        }
        val remainingTarget = targetMarginTotal - knownTotal
        if (remainingTarget <= 0.0) return PharmaMarginRecovery(margins, false)

        val groupQuantities = missingGroups.map { indices ->
            indices.filter { rows[it].taxPerItem <= 0.0 }
                .sumOf { rows[it].quantity.coerceAtLeast(0.0) }
        }
        val solutions = mutableListOf<List<Double>>()
        val assignment = MutableList(missingGroups.size) { 0.0 }

        fun search(groupIndex: Int, subtotal: Double) {
            if (solutions.size > 1) return
            if (groupIndex == missingGroups.size) {
                if (kotlin.math.abs(subtotal - remainingTarget) <= 0.10) {
                    solutions += assignment.toList()
                }
                return
            }

            val quantity = groupQuantities[groupIndex]
            val remainingQuantity = groupQuantities.drop(groupIndex + 1).sum()
            candidates.forEach { candidate ->
                val next = subtotal + candidate * quantity
                val minimumPossible = next + candidates.first() * remainingQuantity
                val maximumPossible = next + candidates.last() * remainingQuantity
                if (remainingTarget + 0.10 < minimumPossible || remainingTarget - 0.10 > maximumPossible) {
                    return@forEach
                }
                assignment[groupIndex] = candidate
                search(groupIndex + 1, next)
            }
        }

        search(0, 0.0)
        if (solutions.size != 1) return PharmaMarginRecovery(margins, false)

        missingGroups.forEachIndexed { groupIndex, indices ->
            indices.filter { rows[it].taxPerItem <= 0.0 }
                .forEach { margins[it] = solutions.single()[groupIndex] }
        }
        val recoveredTotal = rows.indices.sumOf { margins[it] * rows[it].quantity.coerceAtLeast(0.0) }
        return PharmaMarginRecovery(
            margins = margins,
            reconciled = kotlin.math.abs(recoveredTotal - targetMarginTotal) <= 0.10
        )
    }

    /**
     * Reference-only discount for United and Tabark. It is derived from the
     * already-calculated public sale price and purchase cost; it never feeds
     * back into purchase-price calculation or the E-PLUS payload.
     */
    fun referenceDiscountPercent(
        supplierCode: String,
        purchasePrice: Double,
        salePrice: Double
    ): Double? {
        if (supplierCode !in setOf("198", "218") || salePrice <= 0.0) return null
        return roundMoney((salePrice - purchasePrice) / salePrice * 100.0)
    }

    fun calculate(supplierCode: String, item: ExtractedSupplierItem): CalculatedSupplierValues {
        val quantity = item.quantity.coerceAtLeast(1.0)
        val priceBeforeDiscount = item.unitPrice.coerceAtLeast(0.0)
        val netUnitFromPrintedPrice = priceBeforeDiscount * (1.0 - item.discountPercent / 100.0)

        // Pharma Overseas purchase price is calculated independently twice:
        // 1) سعر صيدلي + هامش ثابت للصيدلي.
        // 2) (إجمالي القيمة / الكمية - ض.ق مضافة للوحدة) + هامش ثابت للصيدلي.
        // The raw OCR values stay independent; Android compares the results.
        val tax = when (supplierCode) {
            "29" -> if (item.taxTotal > 0.0) item.taxTotal / quantity else 0.0
            "38" -> when {
                item.taxPerItem > 0.0 -> item.taxPerItem
                item.taxTotal > 0.0 -> item.taxTotal / quantity
                else -> 0.0
            }
            else -> 0.0
        }

        val pharmacistPrice = when (supplierCode) {
            "38" -> item.pharmacistPrice.coerceAtLeast(0.0)
            else -> item.pharmacistPrice.takeIf { it > 0.0 } ?: priceBeforeDiscount
        }

        val rawPharmacistMargin = item.pharmacistMargin.coerceAtLeast(0.0)
        val expectedTaxTotal = item.taxPerItem.coerceAtLeast(0.0) * quantity
        // Ibn Sina and Pharma Overseas do not add a fixed pharmacist margin
        // to taxed rows. Each layout has a different authoritative tax field.
        val itemHasTax = when (supplierCode) {
            "29" -> item.taxTotal > 0.0
            "38" -> item.taxPerItem > 0.0
            else -> false
        }
        // Strong column-shift signature seen in Pharma Overseas OCR: the value
        // under «إجمالي ض.ق.م» is copied into «هامش ثابت للصيدلي». Keep the raw
        // value for review, but never add it to purchase price when it exactly
        // reproduces the row VAT total.
        val marginWasCopiedFromTaxTotal = supplierCode == "38" &&
            rawPharmacistMargin > 0.0 &&
            expectedTaxTotal > 0.0 &&
            kotlin.math.abs(rawPharmacistMargin - expectedTaxTotal) <= PHARMA_COLUMN_COLLISION_TOLERANCE
        val pharmacistMargin = if (itemHasTax || marginWasCopiedFromTaxTotal) {
            0.0
        } else {
            rawPharmacistMargin
        }
        // Two independent calculations per supported supplier. Method one is
        // always the price sent to E-PLUS; method two is validation only.
        val purchaseMethodOne = when (supplierCode) {
            "29", "38" -> if (pharmacistPrice > 0.0) pharmacistPrice + pharmacistMargin else 0.0
            "175" -> priceBeforeDiscount
            "198", "218" -> when {
                item.lineTotal > 0.0 -> item.lineTotal / quantity
                priceBeforeDiscount > 0.0 -> netUnitFromPrintedPrice
                else -> 0.0
            }
            else -> if (item.lineTotal > 0.0) item.lineTotal / quantity else netUnitFromPrintedPrice
        }
        val purchaseMethodTwo = when (supplierCode) {
            "29" -> if (item.lineTotal > 0.0) item.lineTotal / quantity + pharmacistMargin else 0.0
            "38" -> if (item.lineTotal > 0.0) {
                (item.lineTotal / quantity - tax + pharmacistMargin).coerceAtLeast(0.0)
            } else 0.0
            "175" -> if (item.lineTotal > 0.0) item.lineTotal / quantity else 0.0
            "198", "218" -> if (priceBeforeDiscount > 0.0) netUnitFromPrintedPrice else 0.0
            else -> 0.0
        }
        val validationWarnings = if (supplierCode in setOf("29", "38", "175", "198", "218")) buildList {
            if (supplierCode == "38") {
                when {
                    item.taxPerItem > 0.0 && item.taxTotal <= 0.0 -> add("إجمالي ضريبة الصف غير مستخرج")
                    item.taxPerItem <= 0.0 && item.taxTotal > 0.0 -> add("ضريبة الوحدة غير مستخرجة")
                    item.taxPerItem > 0.0 && item.taxTotal > 0.0 &&
                        kotlin.math.abs(expectedTaxTotal - item.taxTotal) >= PHARMA_COMPARISON_TOLERANCE ->
                        add("ضريبة الوحدة × الكمية لا تساوي إجمالي ضريبة الصف")
                }
            }
            if (purchaseMethodOne <= 0.0) add("طريقة سعر الشراء الأولى ناقصة")
            if (purchaseMethodTwo <= 0.0) add("طريقة التحقق الثانية ناقصة")
            if (purchaseMethodOne > 0.0 && purchaseMethodTwo > 0.0 &&
                kotlin.math.abs(purchaseMethodOne - purchaseMethodTwo) >= PHARMA_COMPARISON_TOLERANCE
            ) add("طريقتا حساب سعر الشراء غير متطابقتين")
        } else emptyList()
        val purchaseMethodsMatch = validationWarnings.isEmpty()
        // Unmistakable Pharma OCR column shift: the percentage printed under
        // «خصم الصيدلي» was copied verbatim into «سعر صيدلي». Keep the row red
        // for manual review, but do not send that percentage as a purchase
        // price when the independent total/VAT calculation is available.
        val pharmaPriceLooksLikeDiscount = pharmacistPrice in setOf(18.0, 20.0, 25.0)
        val pharmaPriceCopiedFromDiscount = supplierCode == "38" &&
            pharmacistPrice > 0.0 &&
            purchaseMethodTwo > 0.0 &&
            kotlin.math.abs(purchaseMethodOne - purchaseMethodTwo) >= PHARMA_COMPARISON_TOLERANCE &&
            (
                (item.discountPercent > 0.0 &&
                    kotlin.math.abs(pharmacistPrice - item.discountPercent) <= PHARMA_COLUMN_COLLISION_TOLERANCE) ||
                    // OCR sometimes shifts the discount into pharmacist_price
                    // and leaves discount_percent empty. Pharma's printed
                    // discount values are 18/20/25; require the independent
                    // row-total result to be much larger before using it.
                    (item.discountPercent <= 0.0 && pharmaPriceLooksLikeDiscount &&
                        purchaseMethodTwo >= pharmacistPrice * 1.5)
                )
        val purchasePrice = if (pharmaPriceCopiedFromDiscount) {
            purchaseMethodTwo
        } else {
            purchaseMethodOne
        }

        // United and Tabark print the public price in the generic "price"
        // column. If OCR did not duplicate it into sale_p, use that value as
        // the public price instead of incorrectly treating the item like Dream.
        val extractedPublicSalePrice = when (supplierCode) {
            "198", "218" -> item.suggestedSalePrice.takeIf { it > 0.0 } ?: priceBeforeDiscount
            // ابن سينا وفارما أوفرسيز: sale_p هو سعر الجمهور. consumer_price
            // fallback للتوافق مع ردود OCR القديمة التي وضعته في الحقل الآخر.
            "29", "38" -> item.suggestedSalePrice.takeIf { it > 0.0 }
                ?: item.consumerPrice
            else -> item.suggestedSalePrice
        }
        // بعض أصناف ابن سينا/أوفر سيز لا يكون لها سعر جمهور مطبوعًا. في هذه
        // الحالة قد يكرر OCR سعر الصيدلي داخل sale_p أو consumer_price. لا
        // نسمح أبدًا بتحويل سعر الشراء إلى سعر بيع؛ نعتبر السعر غير مستخرج
        // حتى يراجعه المستخدم يدويًا. السعر الحقيقي الأعلى من تكلفة الشراء
        // يظل صالحًا (مثل الأصناف التي يظهر لها سعر جمهور فعلي).
        val publicSalePrice = when (supplierCode) {
            "29", "38" -> extractedPublicSalePrice.takeIf {
                it > purchasePrice + 0.0005
            } ?: 0.0
            else -> extractedPublicSalePrice
        }
        val shouldUpdateSalePrice = supplierCode != "175" && publicSalePrice > 0.0
        return CalculatedSupplierValues(
            purchasePrice = roundMoney(purchasePrice),
            taxPerItem = roundMoney(tax),
            salePrice = if (shouldUpdateSalePrice) roundMoney(publicSalePrice) else 0.0,
            shouldUpdateSalePrice = shouldUpdateSalePrice,
            priceAlertKind = if (supplierCode == "175") ALERT_PURCHASE_PRICE else ALERT_SALE_PRICE,
            consumerPrice = roundMoney(item.consumerPrice),
            purchasePriceFromPharmacistColumn = roundMoney(purchaseMethodOne),
            purchasePriceFromLineTotal = roundMoney(purchaseMethodTwo),
            purchasePriceMethodsMatch = purchaseMethodsMatch,
            pharmaValidationWarnings = validationWarnings
        )
    }

    private fun roundMoney(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0
}

fun supplierDisplayName(code: String): String = when (code.trim()) {
    "29" -> "ابن سينا فارما"
    "38" -> "فارما أوفر سيز"
    "175" -> "دريم"
    "198" -> "يونايتد جروب / الجمهورية"
    "218" -> "تبارك / مالتي ستورز"
    else -> "مورد غير معروف"
}
