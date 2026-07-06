package com.voice.accountbook.repository

import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.entity.AccountBean
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 统一数据仓库层
 * 所有收支统计、预算计算逻辑仅在此实现一次，供首页/统计页共同调用
 */
class AccountRepository(private val db: AccountDatabase) {

    private val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val sdfDay = SimpleDateFormat("MM-dd", Locale.getDefault())

    // ========== 月份工具方法 ==========

    /** 根据yearMonth("2026-06")返回当月起止时间戳 */
    fun getMonthRange(yearMonth: String): Pair<Long, Long> {
        val parts = yearMonth.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt() - 1
        val cal = Calendar.getInstance()
        cal.set(year, month, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(year, month, cal.getActualMaximum(Calendar.DAY_OF_MONTH), 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return Pair(start, end)
    }

    /** 获取当前年月 */
    fun getCurrentYearMonth(): String = sdf.format(Date())

    /** 月份导航：-1上一个月，+1下一个月，0当前月 */
    fun navigateMonth(current: String, direction: Int): String {
        val parts = current.split("-")
        val cal = Calendar.getInstance()
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, 1)
        cal.add(Calendar.MONTH, direction)
        return sdf.format(cal.time)
    }

    // ========== 预算相关 ==========

    /** 获取指定月份的预算（未设置返回null） */
    suspend fun getBudget(yearMonth: String): Double? {
        return db.budgetDao().getBudget(yearMonth)
    }

    /** 获取默认预算（最近设置的） */
    suspend fun getDefaultBudget(): Double? {
        return db.budgetDao().getDefaultBudget()
    }

    /** 设置月度预算 */
    suspend fun setBudget(yearMonth: String, budget: Double) {
        db.budgetDao().setBudget(
            com.voice.accountbook.entity.BudgetEntity(yearMonth = yearMonth, budget = budget)
        )
    }

    // ========== 收支计算 ==========

    data class MonthSummary(
        val income: Double,
        val expense: Double,
        val balance: Double,
        val budget: Double?,        // null = 未设置预算
        val remainingBudget: Double?, // null = 未设置预算
        val budgetRatio: Float,      // 0~1+，超出为>1
        val yearMonth: String,
        val accounts: List<AccountBean>
    )

    /** 计算指定月份的完整收支概览（唯一计算入口） */
    suspend fun getMonthSummary(yearMonth: String): MonthSummary {
        val (start, end) = getMonthRange(yearMonth)
        val accounts = db.accountDao().getAccountsByMonth(start, end)
        val income = accounts.filter { it.type == 1 }.sumOf { it.money }
        val expense = accounts.filter { it.type == 0 }.sumOf { it.money }
        val balance = income - expense
        val budget = getBudget(yearMonth)
        val remaining = if (budget != null) budget - expense else null
        val ratio = if (budget != null && budget > 0) (expense / budget).toFloat() else 0f
        return MonthSummary(
            income = income, expense = expense, balance = balance,
            budget = budget, remainingBudget = remaining,
            budgetRatio = ratio, yearMonth = yearMonth,
            accounts = accounts
        )
    }

    /** 获取最近记账列表（按时间倒序） */
    suspend fun getRecentAccounts(limit: Int = 3): List<AccountBean> {
        return db.accountDao().getAllAccounts().sortedByDescending { it.time }.take(limit)
    }

    /** 获取全部账户流水 */
    suspend fun getAllAccounts(): List<AccountBean> {
        return db.accountDao().getAllAccounts()
    }

    // ========== 统计页辅助 ==========

    fun getTimeRange(mode: String): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        when (mode) {
            "week" -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val diff = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
                cal.add(Calendar.DAY_OF_MONTH, -diff)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 6)
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                return Pair(start, cal.timeInMillis)
            }
            "year" -> {
                cal.set(Calendar.MONTH, Calendar.JANUARY); cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.MONTH, Calendar.DECEMBER)
                cal.set(Calendar.DAY_OF_MONTH, 31)
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                return Pair(start, cal.timeInMillis)
            }
            else -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                return Pair(start, cal.timeInMillis)
            }
        }
    }

    companion object {
        fun formatAmount(amount: Double): String = String.format("%.2f", amount)

        private var _instance: AccountRepository? = null
        fun getInstance(db: AccountDatabase): AccountRepository {
            return _instance ?: synchronized(this) {
                _instance ?: AccountRepository(db).also { _instance = it }
            }
        }
    }
}