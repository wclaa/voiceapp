package com.voice.accountbook.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.ScaleAnimation
import androidx.recyclerview.widget.RecyclerView
import com.voice.accountbook.databinding.ItemBillDayHeaderBinding
import com.voice.accountbook.databinding.ItemBillRecordBinding
import com.voice.accountbook.viewmodel.BillViewModel
import com.voice.accountbook.util.CategoryIconHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单日期分组适配器
 * 用于显示按天分组的账单记录
 */
class BillDayGroupAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // 数据列表
    private var billDayGroups: List<BillViewModel.BillDayGroup> = emptyList()

    // 条目点击回调
    var onItemClick: ((com.voice.accountbook.entity.AccountBean) -> Unit)? = null
    // 条目长按回调
    var onItemLongClick: ((com.voice.accountbook.entity.AccountBean) -> Unit)? = null

    // 时间格式化器
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 视图类型
    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_RECORD = 1
    }

    /**
     * 提交新数据
     */
    fun submitList(list: List<BillViewModel.BillDayGroup>) {
        billDayGroups = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        // 计算当前位置对应的视图类型
        var currentPosition = 0
        for (group in billDayGroups) {
            // 头部视图
            if (currentPosition == position) {
                return VIEW_TYPE_HEADER
            }
            currentPosition++
            
            // 记录视图
            for (i in 0 until group.accounts.size) {
                if (currentPosition == position) {
                    return VIEW_TYPE_RECORD
                }
                currentPosition++
            }
        }
        return VIEW_TYPE_HEADER
    }

    override fun getItemCount(): Int {
        // 总项数 = 组数 + 所有组的记录数
        return billDayGroups.size + billDayGroups.sumOf { it.accounts.size }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemBillDayHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemBillRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                RecordViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> {
                val group = getGroupByPosition(position)
                holder.bind(group)
            }
            is RecordViewHolder -> {
                val (group, accountIndex) = getRecordByPosition(position)
                holder.bind(group.accounts[accountIndex])
            }
        }
    }

    /**
     * 根据位置获取对应的分组
     */
    private fun getGroupByPosition(position: Int): BillViewModel.BillDayGroup {
        var currentPosition = 0
        for (group in billDayGroups) {
            if (currentPosition == position) {
                return group
            }
            currentPosition += 1 + group.accounts.size
        }
        return billDayGroups[0]
    }

    /**
     * 根据位置获取对应的记录和分组
     */
    private fun getRecordByPosition(position: Int): Pair<BillViewModel.BillDayGroup, Int> {
        var currentPosition = 0
        for (group in billDayGroups) {
            currentPosition++ // 跳过头部
            for (i in 0 until group.accounts.size) {
                if (currentPosition == position) {
                    return Pair(group, i)
                }
                currentPosition++
            }
        }
        return Pair(billDayGroups[0], 0)
    }

    /**
     * 头部ViewHolder
     */
    inner class HeaderViewHolder(private val binding: ItemBillDayHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: BillViewModel.BillDayGroup) {
            binding.tvDate.text = group.displayDate
            binding.tvDayExpense.text = String.format("¥%.2f", group.dayExpense)
        }
    }

    /**
     * 记录ViewHolder
     */
    inner class RecordViewHolder(private val binding: ItemBillRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        // 存储当前绑定的记账记录
        private var account: com.voice.accountbook.entity.AccountBean? = null

        fun getAccount(): com.voice.accountbook.entity.AccountBean? = account

        fun bind(account: com.voice.accountbook.entity.AccountBean) {
            this.account = account
            binding.tvCategory.text = account.category
            binding.tvRemark.text = account.remark
            binding.tvTime.text = timeFormat.format(Date(account.time))
            
            // 设置分类图标
            binding.ivCategoryIcon.setImageResource(CategoryIconHelper.getIconResId(account.category))
            
            // 根据类型显示不同的颜色
            if (account.type == 0) {
                // 支出
                binding.tvAmount.text = String.format("-¥%.2f", account.money)
                binding.tvAmount.setTextColor(binding.root.context.resources.getColor(com.voice.accountbook.R.color.expense))
            } else {
                // 收入
                binding.tvAmount.text = String.format("+¥%.2f", account.money)
                binding.tvAmount.setTextColor(binding.root.context.resources.getColor(com.voice.accountbook.R.color.income))
            }
            
            // 点击条目 → 回调
            binding.root.setOnClickListener {
                startClickAnimation(binding.root)
                onItemClick?.invoke(account)
            }
            // 长按条目 → 回调
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(account)
                true
            }
        }
        
        /**
         * 设置分类图标
         */
        private fun setCategoryIcon(category: String) {
            binding.ivCategoryIcon.setImageResource(CategoryIconHelper.getIconResId(category))
        }
        
        /**
         * 点击动画
         */
        private fun startClickAnimation(view: View) {
            val scaleAnimation = ScaleAnimation(
                1.0f, 0.95f, 1.0f, 0.95f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            )
            scaleAnimation.duration = 100
            scaleAnimation.repeatMode = ScaleAnimation.REVERSE
            scaleAnimation.repeatCount = 1
            view.startAnimation(scaleAnimation)
        }
    }
}
