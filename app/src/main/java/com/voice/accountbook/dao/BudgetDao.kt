package com.voice.accountbook.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voice.accountbook.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 月度预算数据访问对象
 */
@Dao
interface BudgetDao {

    /**
     * 设置/更新月度预算（相同yearMonth会覆盖）
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setBudget(budget: BudgetEntity)

    /**
     * 查询指定月份的预算
     * @return 预算金额，未设置返回null
     */
    @Query("SELECT budget FROM budget WHERE yearMonth = :yearMonth LIMIT 1")
    suspend fun getBudget(yearMonth: String): Double?

    /**
     * 流式查询指定月份的预算
     */
    @Query("SELECT * FROM budget WHERE yearMonth = :yearMonth LIMIT 1")
    fun getBudgetFlow(yearMonth: String): Flow<BudgetEntity?>

    /**
     * 查询默认预算（取最近设置的预算值作为新月份默认值）
     */
    @Query("SELECT budget FROM budget ORDER BY rowid DESC LIMIT 1")
    suspend fun getDefaultBudget(): Double?

    /**
     * 删除所有预算记录
     */
    @Query("DELETE FROM budget")
    suspend fun deleteAll()
}