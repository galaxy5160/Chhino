package com.chhino.di

import android.content.Context
import androidx.room.Room
import com.chhino.data.database.ChhinoDatabase
import com.chhino.data.database.dao.HomeLocationDao
import com.chhino.data.database.dao.RideHistoryDao
import com.chhino.data.database.dao.RuleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ChhinoDatabase {
        return Room.databaseBuilder(
            context,
            ChhinoDatabase::class.java,
            "chhino.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideRideHistoryDao(db: ChhinoDatabase): RideHistoryDao = db.rideHistoryDao()

    @Provides
    fun provideRuleDao(db: ChhinoDatabase): RuleDao = db.ruleDao()

    @Provides
    fun provideHomeLocationDao(db: ChhinoDatabase): HomeLocationDao = db.homeLocationDao()
}
