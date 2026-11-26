package com.chhino.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.chhino.data.database.dao.*
import com.chhino.data.database.entities.*

@Database(
    entities = [
        RideHistoryEntity::class,
        RuleEntity::class,
        HomeLocationEntity::class,
        ExpenseProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class ChhinoDatabase : RoomDatabase() {
    abstract fun rideHistoryDao(): RideHistoryDao
    abstract fun ruleDao(): RuleDao
    abstract fun homeLocationDao(): HomeLocationDao
}
