package com.pharma.link.orderautomating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceTotalCheckTest {
    @Test
    fun totalMatchesWhenRowsReproducePrintedInvoiceTotal() {
        val check = InvoiceTotalCheck.from(
            printedTotal = 105.0,
            items = listOf(
                OcrItem("صنف 1", quantity = 2.0, price = 50.0, taxes = 2.5),
                OcrItem("صنف 2", quantity = 1.0, price = 0.0, taxes = 0.0)
            )
        )

        assertEquals(105.0, check.calculatedTotal, 0.0001)
        assertEquals(0.0, check.difference, 0.0001)
        assertTrue(check.matches)
    }

    @Test
    fun totalIsMarkedDifferentWhenRowsDoNotReproducePrintedTotal() {
        val check = InvoiceTotalCheck.from(
            printedTotal = 100.0,
            items = listOf(OcrItem("صنف", quantity = 2.0, price = 40.0))
        )

        assertEquals(80.0, check.calculatedTotal, 0.0001)
        assertEquals(20.0, check.difference, 0.0001)
        assertFalse(check.matches)
    }

    @Test
    fun subPoundDifferenceIsYellowNotExactMatch() {
        val check = InvoiceTotalCheck.from(
            printedTotal = 100.75,
            items = listOf(OcrItem("صنف", quantity = 1.0, price = 100.0))
        )

        assertFalse(check.matches)
        assertTrue(check.withinOnePound)
    }

    @Test
    fun differenceAboveOnePoundIsRed() {
        val check = InvoiceTotalCheck.from(
            printedTotal = 101.01,
            items = listOf(OcrItem("صنف", quantity = 1.0, price = 100.0))
        )

        assertFalse(check.matches)
        assertFalse(check.withinOnePound)
    }
}
