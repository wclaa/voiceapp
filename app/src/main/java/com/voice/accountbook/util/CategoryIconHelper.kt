package com.voice.accountbook.util

import com.voice.accountbook.R

/**
 * 全局统一分类图标映射
 * 所有页面调用同一方法获取分类图标
 */
object CategoryIconHelper {

    /** 根据分类名返回对应 drawable 资源 ID */
    fun getIconResId(category: String): Int {
        return when (category) {
            "餐饮"       -> R.drawable.ic_cat_food
            "交通"       -> R.drawable.ic_cat_transport
            "购物"       -> R.drawable.ic_cat_shopping
            "娱乐"       -> R.drawable.ic_cat_entertainment
            "医疗"       -> R.drawable.ic_cat_medical
            "教育"       -> R.drawable.ic_cat_education
            "工资"       -> R.drawable.ic_cat_salary
            "红包"       -> R.drawable.ic_cat_redpacket
            "投资"       -> R.drawable.ic_cat_investment
            "通信"       -> R.drawable.ic_cat_comm
            "居家"       -> R.drawable.ic_cat_home
            "转账"       -> R.drawable.ic_cat_salary
            "其他"       -> R.drawable.ic_cat_other
            "其他支出"   -> R.drawable.ic_cat_other
            "其他收入"   -> R.drawable.ic_cat_other
            else         -> R.drawable.ic_cat_other
        }
    }
}