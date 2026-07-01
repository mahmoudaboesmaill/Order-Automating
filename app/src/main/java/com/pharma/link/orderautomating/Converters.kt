package com.pharma.link.orderautomating

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromPriceFormula(value: PriceFormula): String = value.name

    @TypeConverter
    fun toPriceFormula(value: String): PriceFormula =
        PriceFormula.valueOf(value)

    @TypeConverter
    fun fromTaxMode(value: TaxMode): String = value.name

    @TypeConverter
    fun toTaxMode(value: String): TaxMode =
        TaxMode.valueOf(value)
}
