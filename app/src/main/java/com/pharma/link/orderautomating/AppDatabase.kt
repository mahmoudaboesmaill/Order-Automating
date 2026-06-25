package com.pharma.link.orderautomating

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Item::class, PharmacyItem::class, SmartMapping::class, SupplierDictionary::class], version = 14)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun pharmacyItemDao(): PharmacyItemDao
    abstract fun smartMappingDao(): SmartMappingDao
    abstract fun supplierDictionaryDao(): SupplierDictionaryDao

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "order_database"
                )
                .addMigrations(MIGRATION_13_14)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
