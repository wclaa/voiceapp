package com.voice.accountbook.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.entity.AccountBean
import com.voice.accountbook.repository.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DailyTrend(
    val dateLabel: String,
    val income: Double,
    val expense: Double
)

data class CategoryStat(
    val category: String,
    val amount: Double,
    val percentage: Float
)

/**
 * 首页/统计页统一ViewModel
 * 所有数据通过Repository统一获取，计算逻辑仅在Repository实现一次
 * 月度预算与收入完全解耦，存储在独立的budget表
 */
class HomeViewModel(private val accountDatabase: AccountDatabase) : ViewModel() {

    private val repo = AccountRepository.getInstance(accountDatabase)
    private val allAccountsFlow = accountDatabase.accountDao().getAllAccountsFlow()

    // === 月份状态 ===
    private val _currentYearMonth = MutableStateFlow(repo.getCurrentYearMonth())
    val currentYearMonth: StateFlow<String> = _currentYearMonth

    // === 首页数据 ===
    private val _monthlyIncome = MutableStateFlow(0.0)
    val monthlyIncome: StateFlow<Double> = _monthlyIncome

    private val _monthlyExpense = MutableStateFlow(0.0)
    val monthlyExpense: StateFlow<Double> = _monthlyExpense

    private val _monthlyBalance = MutableStateFlow(0.0)
    val monthlyBalance: StateFlow<Double> = _monthlyBalance

    // budget: null=未设置
    private val _monthlyBudget = MutableStateFlow<Double?>(null)
    val monthlyBudget: StateFlow<Double?> = _monthlyBudget

    private val _remainingBudget = MutableStateFlow<Double?>(null)
    val remainingBudget: StateFlow<Double?> = _remainingBudget

    /** 今日还可消费：按日均额度减去当日已支出计算 */
    private val _dailyRemainingBudget = MutableStateFlow<Double?>(null)
    val dailyRemainingBudget: StateFlow<Double?> = _dailyRemainingBudget

    private val _budgetRatio = MutableStateFlow(0f)
    val budgetRatio: StateFlow<Float> = _budgetRatio

    private val _recentAccounts = MutableStateFlow<List<AccountBean>>(emptyList())
    val recentAccounts: StateFlow<List<AccountBean>> = _recentAccounts

    // === 统计页数据 ===
    private val _statsTimeMode = MutableStateFlow("month")
    val statsTimeMode: StateFlow<String> = _statsTimeMode

    private val _statsIncomeType = MutableStateFlow(0)
    val statsIncomeType: StateFlow<Int> = _statsIncomeType

    private val _dailyTrend = MutableStateFlow<List<DailyTrend>>(emptyList())
    val dailyTrend: StateFlow<List<DailyTrend>> = _dailyTrend

    private val _categoryRanking = MutableStateFlow<List<CategoryStat>>(emptyList())
    val categoryRanking: StateFlow<List<CategoryStat>> = _categoryRanking

    private val _dailyAverageExpense = MutableStateFlow(0.0)
    val dailyAverageExpense: StateFlow<Double> = _dailyAverageExpense

    init {
        refreshAll()
        observeAccounts()
    }

    // ========== 月份切换 ==========

    /** 切换月份：-1上一月, +1下一月, 0回到本月 */
    fun navigateMonth(direction: Int) {
        if (direction == 0) {
            _currentYearMonth.value = repo.getCurrentYearMonth()
        } else {
            _currentYearMonth.value = repo.navigateMonth(_currentYearMonth.value, direction)
        }
        refreshAll()
    }

    // ========== 预算设置 ==========

    /** 设置当月预算 */
    fun setMonthlyBudget(budget: Double) {
        viewModelScope.launch {
            repo.setBudget(_currentYearMonth.value, budget)
            refreshMonthData()
        }
    }

    // ========== 数据刷新 ==========

    /** 全量刷新：月份切换时调用 */
    fun refreshAll() {
        viewModelScope.launch {
            refreshMonthData()
            _recentAccounts.value = repo.getRecentAccounts(3)
            recomputeStats()
        }
    }

    /** 仅刷新当月收支+预算 */
    private suspend fun refreshMonthData() {
        val summary = repo.getMonthSummary(_currentYearMonth.value)
        _monthlyIncome.value = summary.income
        _monthlyExpense.value = summary.expense
        _monthlyBalance.value = summary.balance
        _monthlyBudget.value = summary.budget
        _remainingBudget.value = summary.remainingBudget
        _budgetRatio.value = summary.budgetRatio

        // 今日还可消费：日均额度 - 当日已支出
        _dailyRemainingBudget.value = if (summary.budget != null) {
            val cal = Calendar.getInstance()
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val today = cal.get(Calendar.DAY_OF_MONTH)
            val remainingDays = (daysInMonth - today + 1).coerceAtLeast(1)
            val dailyAllowance = (summary.budget - summary.expense) / remainingDays
            val todayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val todayEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
            }.timeInMillis
            val todayExpense = summary.accounts
                .filter { it.type == 0 && it.time in todayStart..todayEnd }
                .sumOf { it.money }
            dailyAllowance - todayExpense
        } else null
    }

    /** 监听全部流水变化，实时刷新 */
    private fun observeAccounts() {
        viewModelScope.launch {
            allAccountsFlow.collect {
                refreshAll()
            }
        }
    }

    // ========== 统计页 ==========

    fun setStatsTimeMode(mode: String) {
        _statsTimeMode.value = mode
        viewModelScope.launch { recomputeStats() }
    }

    fun setStatsIncomeType(type: Int) {
        _statsIncomeType.value = type
        viewModelScope.launch { recomputeStats() }
    }

    private suspend fun recomputeStats() {
        val mode = _statsTimeMode.value
        val (start, end) = repo.getTimeRange(mode)
        val accounts = repo.getAllAccounts()
        val rangeAccounts = accounts.filter { it.time in start..end }

        _dailyTrend.value = buildDailyTrend(mode, rangeAccounts)
        val type = _statsIncomeType.value
        _categoryRanking.value = buildCategoryRanking(rangeAccounts, type)

        val totalExpense = rangeAccounts.filter { it.type == 0 }.sumOf { it.money }
        val days = when (mode) {
            "week" -> 7
            "year" -> Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_YEAR).coerceAtLeast(1)
            else -> Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
        }
        _dailyAverageExpense.value = if (days > 0) totalExpense / days else 0.0
    }

    private fun buildDailyTrend(mode: String, accounts: List<AccountBean>): List<DailyTrend> {
        val sdf = SimpleDateFormat(if (mode == "year") "M月" else "MM/dd", Locale.getDefault())
        val result = mutableListOf<DailyTrend>()

        when (mode) {
            "week" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val diff = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
                cal.add(Calendar.DAY_OF_MONTH, -diff)
                for (i in 0 until 7) {
                    val dayStart = cal.timeInMillis
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                    val dayEnd = cal.timeInMillis - 1
                    val dayAcc = accounts.filter { it.time in dayStart..dayEnd }
                    result.add(DailyTrend(sdf.format(Date(dayStart)),
                        dayAcc.filter { it.type == 1 }.sumOf { it.money },
                        dayAcc.filter { it.type == 0 }.sumOf { it.money }))
                    cal.timeInMillis = dayEnd + 1
                }
            }
            "year" -> {
                val cal = Calendar.getInstance()
                for (m in 1..12) {
                    cal.set(Calendar.MONTH, m - 1); cal.set(Calendar.DAY_OF_MONTH, 1)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    val monthStart = cal.timeInMillis
                    cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                    val monthEnd = cal.timeInMillis
                    val monthAcc = accounts.filter { it.time in monthStart..monthEnd }
                    result.add(DailyTrend("${m}月",
                        monthAcc.filter { it.type == 1 }.sumOf { it.money },
                        monthAcc.filter { it.type == 0 }.sumOf { it.money }))
                }
            }
            else -> {
                val cal = Calendar.getInstance()
                val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                for (d in 1..daysInMonth) {
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    val dayStart = cal.timeInMillis
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                    cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                    val dayEnd = cal.timeInMillis
                    val dayAcc = accounts.filter { it.time in dayStart..dayEnd }
                    result.add(DailyTrend(sdf.format(Date(dayStart)),
                        dayAcc.filter { it.type == 1 }.sumOf { it.money },
                        dayAcc.filter { it.type == 0 }.sumOf { it.money }))
                }
            }
        }
        return result
    }

    private fun buildCategoryRanking(accounts: List<AccountBean>, type: Int): List<CategoryStat> {
        val filtered = accounts.filter { it.type == type }
        if (filtered.isEmpty()) return emptyList()
        val total = filtered.sumOf { it.money }
        if (total == 0.0) return emptyList()
        return filtered.groupBy { it.category }
            .map { (cat, list) ->
                val sum = list.sumOf { it.money }
                CategoryStat(cat, sum, (sum / total * 100f).toFloat())
            }
            .sortedByDescending { it.amount }
    }

    companion object {
        /** 统一金额格式化方法，供所有页面调用 */
        fun formatAmount(amount: Double): String = String.format("%.2f", amount)
    }
}