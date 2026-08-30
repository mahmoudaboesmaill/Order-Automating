package com.pharma.link.orderautomating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewItemOperationsTest {
    private fun item(
        code: String,
        quantity: Double,
        expiryMonth: String = "06"
    ) = OcrItem(
        invoiceName = "Panadol",
        quantity = quantity,
        price = 42.5,
        salePrice = 55.0,
        expiryMonth = expiryMonth,
        expiryYear = "28",
        itmCode = code,
        matched = true
    )

    @Test
    fun splitCreatesAdjacentCopyAndPreservesTheTotalQuantity() {
        val original = item(code = "100", quantity = 10.0)

        val result = requireNotNull(splitReviewItemList(listOf(original), 0, 5.0))

        assertEquals(2, result.size)
        assertEquals(5.0, result[0].quantity, 0.0)
        assertEquals(5.0, result[1].quantity, 0.0)
        assertEquals(10.0, result.sumOf { it.quantity }, 0.0)
        assertEquals(original.itmCode, result[1].itmCode)
        assertEquals(original.price, result[1].price, 0.0)
        assertEquals(original.expiryMonth, result[1].expiryMonth)
        assertEquals(10.0, original.quantity, 0.0)
    }

    @Test
    fun splitRejectsZeroOrTheWholeOriginalQuantity() {
        val items = listOf(item(code = "100", quantity = 10.0))

        assertNull(splitReviewItemList(items, 0, 0.0))
        assertNull(splitReviewItemList(items, 0, 10.0))
        assertNull(splitReviewItemList(items, 0, 11.0))
    }

    @Test
    fun mergeSumsMatchingCodesAndKeepsTheOlderCardDetails() {
        val first = item(code = "100", quantity = 5.0, expiryMonth = "06")
        val second = item(code = "100", quantity = 5.0, expiryMonth = "09")

        val result = requireNotNull(mergeReviewItemList(listOf(first, second), 1))

        assertEquals(1, result.size)
        assertEquals(10.0, result.single().quantity, 0.0)
        assertEquals("06", result.single().expiryMonth)
    }

    @Test
    fun mergeRequiresAnotherNonBlankMatchingCode() {
        assertNull(
            mergeReviewItemList(
                listOf(item(code = "100", quantity = 5.0), item(code = "200", quantity = 5.0)),
                0
            )
        )
        assertNull(
            mergeReviewItemList(
                listOf(item(code = "", quantity = 5.0), item(code = "", quantity = 5.0)),
                0
            )
        )
    }
}
