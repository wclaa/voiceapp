package com.voice.accountbook.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.voice.accountbook.databinding.ItemAccountBinding
import com.voice.accountbook.entity.AccountBean
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 记账记录列表适配器
 */
class AccountListAdapter(private val accountList: List<AccountBean>) :
    RecyclerView.Adapter<AccountListAdapter.AccountViewHolder>() {

    /**
     * 时间格式化器
     */
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /**
     * 创建ViewHolder
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountViewHolder {
        val binding = ItemAccountBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AccountViewHolder(binding)
    }

    /**
     * 绑定数据
     */
    override fun onBindViewHolder(holder: AccountViewHolder, position: Int) {
        val account = accountList[position]
        holder.bind(account)
    }

    /**
     * 获取列表大小
     */
    override fun getItemCount(): Int = accountList.size

    /**
     * 记账记录ViewHolder
     */
    inner class AccountViewHolder(private val binding: ItemAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /**
         * 绑定记账记录数据
         */
        fun bind(account: AccountBean) {
            // 设置金额，根据收支类型显示不同颜色
            binding.tvMoney.text = if (account.type == 0) {
                "-${account.money}"
            } else {
                "+${account.money}"
            }
            binding.tvMoney.setTextColor(
                if (account.type == 0) {
                    binding.root.context.resources.getColor(android.R.color.holo_red_light)
                } else {
                    binding.root.context.resources.getColor(android.R.color.holo_green_light)
                }
            )

            // 设置分类
            binding.tvCategory.text = account.category

            // 设置时间
            binding.tvTime.text = dateFormat.format(account.time)

            // 设置备注
            binding.tvRemark.text = account.remark ?: ""
            binding.tvRemark.visibility = if (account.remark.isNullOrEmpty()) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }
    }
}