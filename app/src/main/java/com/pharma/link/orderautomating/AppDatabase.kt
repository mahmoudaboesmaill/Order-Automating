package com.pharma.link.orderautomating

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Item::class, PharmacyItem::class, SmartMapping::class, SupplierDictionary::class], version = 14)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun pharmacyItemDao(): PharmacyItemDao
    abstract fun smartMappingDao(): SmartMappingDao
    abstract fun supplierDictionaryDao(): SupplierDictionaryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "order_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
