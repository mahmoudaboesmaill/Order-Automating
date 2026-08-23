package com.pharma.link.orderautomating

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Item::class, PharmacyItem::class, SmartMapping::class, 
                SupplierDictionary::class, InvoiceRecord::class, 
                SupplierProfile::class, OcrCorrectionCache::class,
                ExpiryRule::class],
    version = 20
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun pharmacyItemDao(): PharmacyItemDao
    abstract fun smartMappingDao(): SmartMappingDao
    abstract fun supplierDictionaryDao(): SupplierDictionaryDao
    abstract fun invoiceRecordDao(): InvoiceRecordDao
    abstract fun supplierProfileDao(): SupplierProfileDao
    abstract fun ocrCorrectionCacheDao(): OcrCorrectionCacheDao
    abstract fun expiryRuleDao(): ExpiryRuleDao

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

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS ocr_correction_cache (
                        supplierCode TEXT NOT NULL,
                        ocrRawText TEXT NOT NULL,
                        correctedItmCode TEXT NOT NULL,
                        correctedName TEXT NOT NULL,
                        usageCount INTEGER NOT NULL DEFAULT 1,
                        lastUsed INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(supplierCode, ocrRawText)
                    )
                """)
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // حقول تشغيلية للفواتير المرسلة إلى روبوت E-PLUS.
                db.execSQL("ALTER TABLE items ADD COLUMN updateSalePrice INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE items ADD COLUMN priceAlertKind TEXT NOT NULL DEFAULT 'sale_price'")
                db.execSQL("ALTER TABLE items ADD COLUMN invoiceName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN printedTotal REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN difference REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN matchStatus TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN priceChangesCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN expiryPendingCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN ocrProvider TEXT NOT NULL DEFAULT 'auto'")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE invoice_records ADD COLUMN itemsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS expiry_rules (
                        supplierCode TEXT NOT NULL,
                        itmCode TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(supplierCode, itmCode)
                    )
                """.trimIndent())
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
                .addMigrations(
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
