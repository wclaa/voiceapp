package com.voice.accountbook.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.voice.accountbook.entity.AccountBean
import kotlinx.coroutines.flow.Flow

/**
 * 记账数据访问对象
 * 用于操作记账数据库
 */
@Dao
interface AccountDao {
    /**
     * 新增记账记录
     * @param accountBean 记账实体
     */
    @Insert
    suspend fun insert(accountBean: AccountBean)

    /**
     * 根据ID删除记录
     * @param accountBean 记账实体（包含要删除的ID）
     */
    @Delete
    suspend fun delete(accountBean: AccountBean)

    /**
     * 修改记录
     * @param accountBean 记账实体
     */
    @Update
    suspend fun update(accountBean: AccountBean)

    /**
     * 删除所有记录
     */
    @Query("DELETE FROM account")
    suspend fun deleteAll()

    /**
     * 查询所有记录（按时间倒序）
     * @return 记账记录列表
     */
    @Query("SELECT * FROM account ORDER BY time DESC")
    suspend fun getAllAccounts(): List<AccountBean>

    /**
     * 按月份查询当月记录
     * @param startTimestamp 当月开始时间戳
     * @param endTimestamp 当月结束时间戳
     * @return 当月记账记录列表
     */
    @Query("SELECT * FROM account WHERE time >= :startTimestamp AND time <= :endTimestamp ORDER BY time DESC")
    suspend fun getAccountsByMonth(startTimestamp: Long, endTimestamp: Long): List<AccountBean>

    /**
     * 查询当月支出总金额
     * @param startTimestamp 当月开始时间戳
     * @param endTimestamp 当月结束时间戳
     * @return 当月支出总金额
     */
    @Query("SELECT SUM(money) FROM account WHERE type = 0 AND time >= :startTimestamp AND time <= :endTimestamp")
    suspend fun getMonthlyExpense(startTimestamp: Long, endTimestamp: Long): Double?

    /**
     * 查询当月收入总金额
     * @param startTimestamp 当月开始时间戳
     * @param endTimestamp 当月结束时间戳
     * @return 当月收入总金额
     */
    @Query("SELECT SUM(money) FROM account WHERE type = 1 AND time >= :startTimestamp AND time <= :endTimestamp")
    suspend fun getMonthlyIncome(startTimestamp: Long, endTimestamp: Long): Double?

    /**
     * 查询本月所有支出记录
     * @param startTimestamp 当月开始时间戳
     * @param endTimestamp 当月结束时间戳
     * @return 当月支出记录列表
     */
    @Query("SELECT * FROM account WHERE time >= :startTimestamp AND time <= :endTimestamp ORDER BY time DESC")
    fun getMonthlyAccounts(startTimestamp: Long, endTimestamp: Long): kotlinx.coroutines.flow.Flow<List<com.voice.accountbook.entity.AccountBean>>

    /**
     * 按日期分组查询所有记录
     * @return 所有记录列表，按时间倒序排列
     */
    @Query("SELECT * FROM account ORDER BY time DESC")
    fun getAllAccountsFlow(): kotlinx.coroutines.flow.Flow<List<com.voice.accountbook.entity.AccountBean>>
}