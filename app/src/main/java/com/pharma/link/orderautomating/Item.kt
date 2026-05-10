package com.pharma.link.orderautomating

data class Item(
    val itmCode: String,
    val quantity: Int,
    val price: Double,
    val discount: Double = 0.0
)