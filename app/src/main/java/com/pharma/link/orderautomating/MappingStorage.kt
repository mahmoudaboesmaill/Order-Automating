package com.pharma.link.orderautomating

import android.content.Context
import org.json.JSONObject
import java.io.File

object MappingStorage {
    private const val FILE_NAME = "supplier_mapping.json"

    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    fun getMapping(context: Context, supplierCode: String, invoiceName: String): String? {
        return try {
            val json = JSONObject(getFile(context).readText())
            json.optJSONObject(supplierCode)?.optString(invoiceName)?.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    fun saveMapping(context: Context, supplierCode: String, invoiceName: String, itmCode: String) {
        val file = getFile(context)
        val json = if (file.exists()) JSONObject(file.readText()) else JSONObject()
        val supplier = json.optJSONObject(supplierCode) ?: JSONObject()
        supplier.put(invoiceName, itmCode)
        json.put(supplierCode, supplier)
        file.writeText(json.toString(2))
    }
}