package com.pharma.link.orderautomating

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Item::class, PharmacyItem::class, SmartMapping::class, SupplierDictionary::class, InvoiceRecord::class], version = 15)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun pharmacyItemDao(): PharmacyItemDao
    abstract fun smartMappingDao(): SmartMappingDao
    abstract fun supplierDictionaryDao(): SupplierDictionaryDao
    abstract fun invoiceRecordDao(): InvoiceRecordDao

    companion object {
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // إنشاء جدول supplier_dictionary لو مش موجود
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS supplier_dictionary (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        arabicName TEXT NOT NULL,
                        englishName TEXT NOT NULL,
                        supplierCode TEXT NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS invoice_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        supplierCode TEXT NOT NULL,
                        supplierName TEXT NOT NULL,
                        invoiceNumber TEXT NOT NULL,
                        itemsCount INTEGER NOT NULL,
                        totalPrice REAL NOT NULL,
                        sentAt INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'success'
                    )
                """)
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "order_database"
                )
                .addMigrations(MIGRATION_13_14, MIGRATION_14_15)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
