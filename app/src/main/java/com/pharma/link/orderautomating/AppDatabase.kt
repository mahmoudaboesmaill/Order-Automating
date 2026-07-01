package com.pharma.link.orderautomating

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Item::class, PharmacyItem::class, SmartMapping::class, 
                SupplierDictionary::class, InvoiceRecord::class, 
                SupplierProfile::class], 
    version = 16
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun pharmacyItemDao(): PharmacyItemDao
    abstract fun smartMappingDao(): SmartMappingDao
    abstract fun supplierDictionaryDao(): SupplierDictionaryDao
    abstract fun invoiceRecordDao(): InvoiceRecordDao
    abstract fun supplierProfileDao(): SupplierProfileDao

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

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS supplier_profiles (
                        supplierCode TEXT NOT NULL PRIMARY KEY,
                        priceFormula TEXT NOT NULL DEFAULT 'UNIT_PRICE',
                        taxMode TEXT NOT NULL DEFAULT 'PER_INVOICE',
                        hasSalePrice INTEGER NOT NULL DEFAULT 1,
                        hasBonus INTEGER NOT NULL DEFAULT 1,
                        columnHint TEXT NOT NULL DEFAULT ''
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
                .addMigrations(MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
