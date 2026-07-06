package com.voice.accountbook.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 月度预算实体
 * yearMonth为主键，每个月份独立存储一条记录，同月重复设置直接覆盖
 */
@Entity(tableName = "budget")
data class BudgetEntity(
    /** 年月标识，如 "2026-06"（主键，每月唯一） */
    @PrimaryKey
    val yearMonth: String,

    /** 月度总预算（支出上限） */
    val budget: Double
)