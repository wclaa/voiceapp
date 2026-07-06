package com.voice.accountbook.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.voice.accountbook.dao.AccountDao
import com.voice.accountbook.dao.BudgetDao
import com.voice.accountbook.entity.AccountBean
import com.voice.accountbook.entity.BudgetEntity

/**
 * 记账数据库
 * 实现Room数据库单例模式
 */
@Database(
    entities = [AccountBean::class, BudgetEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AccountDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        private var instance: AccountDatabase? = null

        @Synchronized
        fun getInstance(context: Context): AccountDatabase {
            if (instance == null) {
                instance = Room.databaseBuilder(
                    context.applicationContext,
                    AccountDatabase::class.java,
                    "account_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
            }
            return instance!!
        }
    }
}