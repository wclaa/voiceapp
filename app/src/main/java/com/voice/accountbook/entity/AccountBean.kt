package com.voice.accountbook.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记账实体类
 * 用于存储记账数据
 */
@Entity(tableName = "account")
data class AccountBean(
    /**
     * 主键，自增长
     */
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    /**
     * 金额
     */
    val money: Double,
    
    /**
     * 收支类型：0=支出，1=收入
     */
    val type: Int,
    
    /**
     * 分类：餐饮、交通、购物、娱乐、医疗、住房、教育、社交、其他
     */
    val category: String,
    
    /**
     * 时间戳
     */
    val time: Long,
    
    /**
     * 备注
     */
    val remark: String? = null
)