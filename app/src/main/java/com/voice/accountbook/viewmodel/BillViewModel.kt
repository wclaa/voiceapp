package com.voice.accountbook.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.entity.AccountBean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 账单页面ViewModel
 * 处理账单页面的数据逻辑
 */
class BillViewModel(private val accountDatabase: AccountDatabase) : ViewModel() {

    // 本月总支出
    private val _monthlyExpense = MutableStateFlow(0.0)
    val monthlyExpense: StateFlow<Double> = _monthlyExpense

    // 按天分组的账单数据
    private val _billDayGroups = MutableStateFlow<List<BillDayGroup>>(emptyList())
    val billDayGroups: StateFlow<List<BillDayGroup>> = _billDayGroups

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("MM月dd日 EEEE", Locale.getDefault())

    init {
        // 初始化时加载数据
        loadBillData()
    }

    /**
     * 加载账单数据
     */
    fun loadBillData(filterCategory: String? = null, filterType: Int? = null) {
        viewModelScope.launch {
            // 监听所有记账记录的变化
            accountDatabase.accountDao().getAllAccountsFlow().collect {
                accounts ->
                // 获取当月时间范围
                val (startTimestamp, endTimestamp) = getCurrentMonthTimeRange()

                // 计算本月总支出
                val expense = accountDatabase.accountDao().getMonthlyExpense(startTimestamp, endTimestamp) ?: 0.0
                _monthlyExpense.value = expense

                // 按天分组处理（应用分类/类型筛选）
                val filtered = accounts.filter { account ->
                    val timeInRange = account.time in startTimestamp..endTimestamp
                    val categoryMatch = filterCategory == null || account.category == filterCategory
                    val typeMatch = filterType == null || account.type == filterType
                    timeInRange && categoryMatch && typeMatch
                }
                val groups = groupAccountsByDay(filtered)
                _billDayGroups.value = groups
            }
        }
    }

    /**
     * 按天分组记账记录
     */
    private fun groupAccountsByDay(accounts: List<AccountBean>): List<BillDayGroup> {
        // 按日期分组
        val grouped = accounts.groupBy {
            dateFormat.format(Date(it.time))
        }

        // 转换为BillDayGroup列表
        return grouped.map { (dateStr, accountList) ->
            val date = dateFormat.parse(dateStr) ?: Date()
            val displayDate = formatDisplayDate(date)
            val dayExpense = accountList.filter { it.type == 0 }.sumOf { it.money }
            
            BillDayGroup(
                date = dateStr,
                displayDate = displayDate,
                dayExpense = dayExpense,
                accounts = accountList
            )
        }.sortedByDescending { it.date } // 按日期倒序排列
    }

    /**
     * 格式化显示日期
     */
    private fun formatDisplayDate(date: Date): String {
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dateCal = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }

        return when {
            dateCal.timeInMillis == today.timeInMillis -> "今天"
            dateCal.timeInMillis == yesterday.timeInMillis -> "昨天"
            else -> displayDateFormat.format(date)
        }
    }

    /**
     * 获取当月时间范围
     */
    private fun getCurrentMonthTimeRange(): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        // 当月第一天
        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 当月最后一天
        val endCalendar = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return Pair(startCalendar.timeInMillis, endCalendar.timeInMillis)
    }

    /**
     * 账单日期分组数据类
     */
    data class BillDayGroup(
        val date: String, // 日期字符串，格式：yyyy-MM-dd
        val displayDate: String, // 显示用日期，格式：今天/昨天/MM月dd日 周几
        val dayExpense: Double, // 当日总支出
        val accounts: List<AccountBean> // 当日记账记录
    )

    /**
     * 删除记账记录
     */
    fun deleteAccount(account: AccountBean) {
        viewModelScope.launch {
            accountDatabase.accountDao().delete(account)
            // Flow自动刷新数据，无需手动调用loadBillData
        }
    }

    /**
     * 更新记账记录
     */
    fun updateAccount(account: AccountBean) {
        viewModelScope.launch {
            accountDatabase.accountDao().update(account)
            // Flow自动刷新数据，无需手动调用loadBillData
        }
    }
}
