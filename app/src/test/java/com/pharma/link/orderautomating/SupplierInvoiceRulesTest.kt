package com.pharma.link.orderautomating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupplierInvoiceRulesTest {
    @Test
    fun pharmaOverseasNeverSendsDiscountColumnAsPurchasePrice() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 3.0,
                discountPercent = 20.0,
                lineTotal = 228.0,
                pharmacistPrice = 20.0,
                pharmacistMargin = 0.0,
                taxPerItem = 9.33,
                taxTotal = 27.99,
                suggestedSalePrice = 95.0
            )
        )

        assertEquals(66.67, result.purchasePrice, 0.001)
        assertEquals(20.0, result.purchasePriceFromPharmacistColumn, 0.001)
        assertEquals(66.67, result.purchasePriceFromLineTotal, 0.001)
        assertFalse(result.purchasePriceMethodsMatch)
    }

    @Test
    fun pharmaOverseasDetectsShiftedDiscountEvenWhenDiscountFieldIsEmpty() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 3.0,
                discountPercent = 0.0,
                lineTotal = 228.0,
                pharmacistPrice = 20.0,
                taxPerItem = 9.33,
                taxTotal = 27.99
            )
        )

        assertEquals(66.67, result.purchasePrice, 0.001)
        assertFalse(result.purchasePriceMethodsMatch)
    }

    @Test
    fun ibnSinaTaxedItemDoesNotAddPharmacistMargin() {
        val result = SupplierInvoiceRules.calculate(
            "29",
            ExtractedSupplierItem(
                quantity = 5.0,
                pharmacistPrice = 5.0,
                pharmacistMargin = 1.0,
                taxTotal = 5.0,
                suggestedSalePrice = 10.0
            )
        )

        assertEquals(5.0, result.purchasePrice, 0.0001)
        assertEquals(1.0, result.taxPerItem, 0.0001)
        assertEquals(10.0, result.salePrice, 0.0001)
        assertTrue(result.shouldUpdateSalePrice)
        assertEquals(SupplierInvoiceRules.ALERT_SALE_PRICE, result.priceAlertKind)
    }

    @Test
    fun ibnSinaSplitsPrintedTaxTotalAndSuppressesMargin() {
        // Epoetin-style row: pharmacist price 155.2, pharmacist margin 1,
        // distributor margin is not included, and tax total is 2 for 2 units.
        val result = SupplierInvoiceRules.calculate(
            "29",
            ExtractedSupplierItem(
                quantity = 2.0,
                pharmacistPrice = 155.2,
                pharmacistMargin = 1.0,
                taxTotal = 2.0,
                suggestedSalePrice = 196.0
            )
        )

        assertEquals(155.2, result.purchasePrice, 0.0001)
        assertEquals(1.0, result.taxPerItem, 0.0001)
        assertEquals(196.0, result.salePrice, 0.0001)
    }

    @Test
    fun ibnSinaUsesPrintedTaxTotalAsTheOnlyTaxSource() {
        val result = SupplierInvoiceRules.calculate(
            "29",
            ExtractedSupplierItem(
                quantity = 2.0,
                pharmacistPrice = 155.2,
                pharmacistMargin = 1.0,
                taxTotal = 0.0,
                taxPerItem = 1.0,
                suggestedSalePrice = 196.0
            )
        )

        assertEquals(156.2, result.purchasePrice, 0.0001)
        assertEquals(0.0, result.taxPerItem, 0.0001)
    }

    @Test
    fun ibnSinaDoesNotTreatPurchasePriceAsMissingPublicSalePrice() {
        // MY SWEET BABY-style row: سعر الجمهور is blank, while سعر الصيدلي is
        // 52.5. OCR may copy 52.5 into sale_p; that must not update E-PLUS.
        val result = SupplierInvoiceRules.calculate(
            "29",
            ExtractedSupplierItem(
                quantity = 10.0,
                pharmacistPrice = 52.5,
                pharmacistMargin = 0.0,
                suggestedSalePrice = 52.5,
                consumerPrice = 52.5
            )
        )

        assertEquals(52.5, result.purchasePrice, 0.0001)
        assertEquals(0.0, result.salePrice, 0.0001)
        assertFalse(result.shouldUpdateSalePrice)
    }

    @Test
    fun pharmaOverseasIgnoresPharmacyMarginWhenItemHasTax() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 2.0,
                pharmacistPrice = 30.0,
                pharmacistMargin = 2.0,
                lineTotal = 63.0,
                taxPerItem = 1.5,
                taxTotal = 3.0,
                suggestedSalePrice = 56.0
            )
        )

        assertEquals(30.0, result.purchasePrice, 0.0001)
        assertEquals(30.0, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(30.0, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
        assertEquals(1.5, result.taxPerItem, 0.0001)
    }

    @Test
    fun pharmaOverseasRejectsDifferentResultsInsteadOfReplacingPrintedPrice() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 5.0,
                unitPrice = 60.0,
                discountPercent = 25.0,
                lineTotal = 206.25,
                pharmacistPrice = 27.99,
                pharmacistMargin = 25.0,
                distributorMargin = 2.5,
                taxPerItem = 0.0,
                suggestedSalePrice = 60.0
            )
        )

        assertEquals(52.99, result.purchasePrice, 0.0001)
        assertEquals(52.99, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(66.25, result.purchasePriceFromLineTotal, 0.0001)
        assertFalse(result.purchasePriceMethodsMatch)
        assertEquals(60.0, result.salePrice, 0.0001)
    }

    @Test
    fun pharmaOverseasUsesRowVatWhenReconcilingPurchasePrice() {
        // 228 / 3 - 9.33 = 66.67; no fixed margin on this row.
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 3.0,
                discountPercent = 20.0,
                lineTotal = 228.0,
                pharmacistPrice = 66.67,
                pharmacistMargin = 0.0,
                distributorMargin = 0.0,
                taxPerItem = 9.33,
                taxTotal = 27.99,
                suggestedSalePrice = 95.0
            )
        )

        assertEquals(66.67, result.purchasePrice, 0.0001)
        assertEquals(66.67, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(66.67, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
        assertEquals(9.33, result.taxPerItem, 0.0001)
    }

    @Test
    fun pharmaOverseasIgnoresVatTotalCopiedIntoPharmacistMargin() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 3.0,
                lineTotal = 228.0,
                pharmacistPrice = 66.67,
                pharmacistMargin = 27.99,
                taxPerItem = 9.33,
                taxTotal = 27.99
            )
        )

        assertEquals(66.67, result.purchasePrice, 0.0001)
        assertEquals(66.67, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(66.67, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
    }

    @Test
    fun pharmaOverseasAlwaysIgnoresMarginOnTaxedItems() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 3.0,
                lineTotal = 228.0,
                pharmacistPrice = 66.67,
                pharmacistMargin = 4.25,
                taxPerItem = 9.33,
                taxTotal = 27.99
            )
        )

        assertEquals(66.67, result.purchasePrice, 0.0001)
        assertEquals(66.67, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(66.67, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
    }

    @Test
    fun pharmaOverseasAddsMarginWhenPerUnitTaxIsZero() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 5.0,
                lineTotal = 206.25,
                pharmacistPrice = 41.25,
                pharmacistMargin = 2.50,
                taxPerItem = 0.0,
                taxTotal = 0.0
            )
        )

        assertEquals(43.75, result.purchasePrice, 0.0001)
        assertEquals(43.75, result.purchasePriceFromPharmacistColumn, 0.0001)
        assertEquals(43.75, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
    }

    @Test
    fun pharmaOverseasFlagsDroppedTaxDecimals() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 1.0,
                lineTotal = 48.0,
                pharmacistPrice = 42.11,
                pharmacistMargin = 0.0,
                taxPerItem = 5.0,
                taxTotal = 5.89
            )
        )

        assertFalse(result.purchasePriceMethodsMatch)
        assertTrue(result.pharmaValidationWarnings.any { it.contains("ضريبة الوحدة") })
    }

    @Test
    fun pharmaOverseasAcceptsSubHalfPoundRoundingAndUsesFirstMethod() {
        val result = SupplierInvoiceRules.calculate(
            "38",
            ExtractedSupplierItem(
                quantity = 2.0,
                lineTotal = 231.24,
                pharmacistPrice = 115.60,
                pharmacistMargin = 0.0,
                taxPerItem = 0.0,
                taxTotal = 0.0
            )
        )

        assertEquals(115.60, result.purchasePrice, 0.0001)
        assertEquals(115.62, result.purchasePriceFromLineTotal, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
        assertTrue(result.pharmaValidationWarnings.isEmpty())
    }

    @Test
    fun unitedAndTabarkUsePrintedLineTotal() {
        val result = SupplierInvoiceRules.calculate(
            "218",
            ExtractedSupplierItem(
                quantity = 2.0,
                unitPrice = 120.0,
                discountPercent = 32.0,
                lineTotal = 163.2,
                suggestedSalePrice = 120.0
            )
        )

        assertEquals(81.6, result.purchasePrice, 0.0001)
        assertEquals(120.0, result.salePrice, 0.0001)
    }

    @Test
    fun referenceDiscountIsCalculatedOnlyForUnitedAndTabark() {
        assertEquals(
            20.0,
            SupplierInvoiceRules.referenceDiscountPercent("218", 80.0, 100.0) ?: -1.0,
            0.0001
        )
        assertEquals(
            20.0,
            SupplierInvoiceRules.referenceDiscountPercent("198", 80.0, 100.0) ?: -1.0,
            0.0001
        )
        assertEquals(
            null,
            SupplierInvoiceRules.referenceDiscountPercent("29", 80.0, 100.0)
        )
    }

    @Test
    fun unitedUsesUnitPriceAsPublicPriceWhenSalePriceColumnWasNotDuplicated() {
        val result = SupplierInvoiceRules.calculate(
            "198",
            ExtractedSupplierItem(
                quantity = 5.0,
                unitPrice = 24.0,
                lineTotal = 88.8
            )
        )

        assertEquals(17.76, result.purchasePrice, 0.0001)
        assertEquals(24.0, result.salePrice, 0.0001)
        assertTrue(result.shouldUpdateSalePrice)
    }

    @Test
    fun dreamUpdatesCostButNeverUpdatesEplusSalePrice() {
        val result = SupplierInvoiceRules.calculate(
            "175",
            ExtractedSupplierItem(
                quantity = 3.0,
                unitPrice = 53.5,
                consumerPrice = 75.0,
                suggestedSalePrice = 75.0
            )
        )

        assertEquals(53.5, result.purchasePrice, 0.0001)
        assertEquals(75.0, result.consumerPrice, 0.0001)
        assertEquals(0.0, result.salePrice, 0.0001)
        assertFalse(result.shouldUpdateSalePrice)
        assertEquals(SupplierInvoiceRules.ALERT_PURCHASE_PRICE, result.priceAlertKind)
    }

    @Test
    fun dreamChecksPrintedUnitCostAgainstLineTotalPerQuantity() {
        val result = SupplierInvoiceRules.calculate(
            "175",
            ExtractedSupplierItem(
                quantity = 4.0,
                unitPrice = 25.0,
                lineTotal = 100.0,
                consumerPrice = 35.0
            )
        )

        assertEquals(25.0, result.purchasePrice, 0.0001)
        assertTrue(result.purchasePriceMethodsMatch)
    }

    @Test
    fun ibnSinaAllowsBlankPublicPriceAndChecksTotalWithoutTax() {
        val result = SupplierInvoiceRules.calculate(
            "29",
            ExtractedSupplierItem(
                quantity = 2.0,
                pharmacistPrice = 50.0,
                pharmacistMargin = 2.0,
                lineTotal = 100.0,
                suggestedSalePrice = 0.0
            )
        )

        assertEquals(52.0, result.purchasePrice, 0.0001)
        assertEquals(0.0, result.salePrice, 0.0001)
        assertFalse(result.shouldUpdateSalePrice)
        assertTrue(result.purchasePriceMethodsMatch)
    }

    @Test
    fun pharmaRecoversFaintMarginsFromTheInvoiceWideUniqueSolution() {
        val recovery = SupplierInvoiceRules.recoverPharmaMargins(
            invoiceTotal = 3704.94,
            rows = listOf(
                PharmaMarginRow("betadine", 3.0, 228.0, 9.33, 0.0),
                PharmaMarginRow("daviton", 1.0, 48.0, 5.89, 0.0),
                PharmaMarginRow("ketolac", 5.0, 206.25, 0.0, 2.5),
                PharmaMarginRow("depakine", 2.0, 231.24, 0.0, 1.5),
                PharmaMarginRow("cortilaser", 10.0, 292.50, 0.0, 0.0),
                PharmaMarginRow("oxazolid|128", 1.0, 128.0, 0.0, 1.0),
                PharmaMarginRow("oxazolid|128", 1.0, 128.0, 0.0, 0.0),
                PharmaMarginRow("sleep-ez", 5.0, 281.25, 0.0, 0.0),
                PharmaMarginRow("mixtard|104.96", 14.0, 1469.40, 0.0, 0.0),
                PharmaMarginRow("mixtard|104.96", 6.0, 629.76, 0.0, 0.0)
            )
        )

        assertTrue(recovery.reconciled)
        assertEquals(
            listOf(0.0, 0.0, 2.5, 1.5, 1.0, 1.0, 1.0, 1.0, 1.5, 1.5),
            recovery.margins
        )
    }

    @Test
    fun pharmaDoesNotInventMarginsWhenMoreThanOneSolutionExists() {
        val recovery = SupplierInvoiceRules.recoverPharmaMargins(
            invoiceTotal = 43.0,
            rows = listOf(
                PharmaMarginRow("known-one", 1.0, 10.0, 0.0, 1.0),
                PharmaMarginRow("known-two", 1.0, 10.0, 0.0, 2.0),
                PharmaMarginRow("missing-one", 1.0, 10.0, 0.0, 0.0),
                PharmaMarginRow("missing-two", 1.0, 10.0, 0.0, 0.0)
            )
        )

        assertFalse(recovery.reconciled)
        assertEquals(listOf(1.0, 2.0, 0.0, 0.0), recovery.margins)
    }
}
