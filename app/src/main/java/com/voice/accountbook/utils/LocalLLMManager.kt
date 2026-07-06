package com.voice.accountbook.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.seg.common.Term
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 本地语义解析工具类
 * 使用纯原生的正则表达式和关键词匹配实现记账语义解析
 */
class LocalLLMManager private constructor(private val context: Context) {

    // 日志标签
    private val TAG = "LocalLLMManager"

    // 线程池
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 回调接口
    private var callback: LLMCallback? = null
    
    // 修复：添加初始化状态控制
    private var isInitializing = false
    private var isInitialized = false

    /**
     * LLM回调接口
     */
    interface LLMCallback {
        /**
         * 模型加载进度回调
         * @param progress 进度值（0-100）
         */
        fun onLoadProgress(progress: Int)

        /**
         * 模型加载成功回调
         */
        fun onLoadSuccess()

        /**
         * 模型加载失败回调
         * @param error 错误信息
         */
        fun onLoadFail(error: String)

        /**
         * 推理生成成功回调
         * @param result 最终的JSON字符串
         */
        fun onGenerateSuccess(result: String)

        /**
         * 推理生成失败回调
         * @param error 错误信息
         */
        fun onGenerateFail(error: String)
    }

    /**
     * 初始化模型
     * @param callback 回调接口
     */
    fun initModel(callback: LLMCallback) {
        this.callback = callback

        // 修复：防止重复初始化
        if (isInitializing) {
            Log.d(TAG, "模型正在初始化中，跳过重复调用")
            return
        }

        if (isInitialized) {
            Log.d(TAG, "模型已初始化成功，直接返回")
            mainHandler.post {
                callback.onLoadSuccess()
            }
            return
        }

        isInitializing = true

        executor.execute {
            var initSuccess = false
            try {
                // 初始化HanLP
                mainHandler.post {
                    callback.onLoadProgress(20)
                }
                try {
                    // HanLP portable版本不需要显式初始化
                    Log.d(TAG, "HanLP初始化成功")
                } catch (e: Exception) {
                    Log.e(TAG, "HanLP初始化失败", e)
                }
                Thread.sleep(100)
                
                // 模拟加载进度
                mainHandler.post {
                    callback.onLoadProgress(50)
                }
                Thread.sleep(200)
                
                mainHandler.post {
                    callback.onLoadProgress(80)
                }
                Thread.sleep(200)
                
                mainHandler.post {
                    callback.onLoadProgress(100)
                    callback.onLoadSuccess()
                }
                initSuccess = true
                isInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "初始化失败", e)
                mainHandler.post {
                    callback.onLoadFail("初始化失败: ${e.message}")
                }
            } finally {
                isInitializing = false
                // 修复：确保即使在异常情况下，初始化状态也被正确重置
                if (!initSuccess) {
                    isInitialized = false
                }
            }
        }
    }

    /**
     * 推理生成
     * @param userInput 用户输入的语音识别文本
     */
    fun generate(userInput: String) {
        executor.execute {
            try {
                Log.d(TAG, "开始语义解析: $userInput")
                
                // 使用纯原生的语义解析逻辑
                val result = parseAccountingText(userInput)
                Log.d(TAG, "解析结果: $result")
                
                // 检查金额是否为0
                val jsonResult = JSONObject(result)
                val amount = jsonResult.getDouble("amount")
                if (amount == 0.0) {
                    mainHandler.post {
                        callback?.onGenerateFail("无法识别金额，请重新输入")
                    }
                    return@execute
                }
                
                mainHandler.post {
                    callback?.onGenerateSuccess(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析失败", e)
                mainHandler.post {
                    callback?.onGenerateFail("解析失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 解析记账文本
     * @param text 语音识别文本
     * @return 结构化的JSON字符串
     */
    private fun parseAccountingText(text: String): String {
        // 收支类型识别
        val type = if (text.contains("收入") || text.contains("赚") || text.contains("工资") || text.contains("红包")) {
            "income"
        } else {
            "expense"
        }
        
        // 金额提取
        val amount = extractAmount(text)
        
        // 分类识别
        val category = extractCategory(text, type)
        
        // 时间提取
        val (date, time) = extractDateTime(text)
        
        // 备注提取
        val note = extractNote(text, amount, category)
        
        // 构建JSON
        return "{" +
            "\"type\":\"$type\"," +
            "\"amount\":$amount," +
            "\"category\":\"$category\"," +
            "\"date\":\"$date\"," +
            "\"time\":\"$time\"," +
            "\"note\":\"$note\"" +
            "}"
    }

    /**
     * 提取金额
     * @param text 文本
     * @return 金额
     */
    private fun extractAmount(text: String): Double {
        try {
            // 1. 先尝试匹配阿拉伯数字+单位的模式
            val arabicPattern = Pattern.compile("(\\d+(\\.\\d+)?)(元|块|钱|角|分)")
            val arabicMatcher = arabicPattern.matcher(text)
            
            if (arabicMatcher.find()) {
                return arabicMatcher.group(1).toDouble()
            }
            
            // 2. 尝试使用HanLP分词识别中文数字和金额
            try {
                val terms = HanLP.newSegment().seg(text)
                var amount = 0.0
                var tempNumber = 0.0
                var currentUnit = 1.0
                var hasNumber = false
                var inAmount = false
                
                for (i in terms.indices) {
                    val term = terms[i]
                    val word = term.word
                    // 检查是否是数字或单位
                    when {
                        word.matches("\\d+".toRegex()) -> { // 数字
                            try {
                                val num = word.toDouble()
                                tempNumber = num
                                hasNumber = true
                                inAmount = true
                            } catch (e: NumberFormatException) {
                                Log.e(TAG, "数字转换失败: $word", e)
                            }
                        }
                        word in listOf("十", "百", "千", "万") -> { // 量词
                            when (word) {
                                "十" -> currentUnit = 10.0
                                "百" -> currentUnit = 100.0
                                "千" -> currentUnit = 1000.0
                                "万" -> currentUnit = 10000.0
                            }
                            if (hasNumber) {
                                amount += tempNumber * currentUnit
                                tempNumber = 0.0
                                currentUnit = 1.0
                            }
                        }
                        word in listOf("元", "块", "钱", "角", "分") -> {
                            // 遇到金额单位，结束金额识别
                            if (hasNumber) {
                                amount += tempNumber
                            }
                            break
                        }
                        inAmount && word in listOf("块", "元", "钱") -> {
                            // 处理"五十块"这样的情况
                            if (hasNumber) {
                                amount += tempNumber
                            }
                            break
                        }
                    }
                }
                
                // 3. 特殊处理"五十"、"一百"这样的情况
                if (amount == 0.0 && hasNumber) {
                    amount = tempNumber
                }
                
                // 4. 如果HanLP分词识别到了金额，返回结果
                if (amount > 0.0) {
                    return amount
                }
            } catch (e: Exception) {
                Log.e(TAG, "HanLP分词失败", e)
            }
            
            // 5. 使用兜底的中文数字解析
            return extractChineseAmount(text)
        } catch (e: Exception) {
            Log.e(TAG, "提取金额失败", e)
            // 发生异常时，使用兜底的中文数字解析
            return extractChineseAmount(text)
        }
    }
    
    /**
     * 兜底的中文数字解析
     * @param text 文本
     * @return 金额
     */
    private fun extractChineseAmount(text: String): Double {
        val numMap = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
            "十" to 10, "百" to 100, "千" to 1000, "万" to 10000
        )
        
        // 尝试匹配中文数字+单位
        val pattern = Regex("([零一二三四五六七八九十百千万]+)块?|(\\d+(\\.\\d+)?)块?")
        val match = pattern.find(text) ?: return 0.0
        
        // 处理阿拉伯数字
        if (match.groupValues[2].isNotEmpty()) {
            return match.groupValues[2].toDoubleOrNull() ?: 0.0
        }
        
        // 处理中文数字
        val chineseNum = match.groupValues[1]
        var result = 0.0
        var temp = 0
        
        for (c in chineseNum) {
            val num = numMap[c.toString()] ?: 0
            when (num) {
                10 -> temp = if (temp == 0) 10 else temp * 10
                100 -> temp = if (temp == 0) 100 else temp * 100
                1000 -> temp = if (temp == 0) 1000 else temp * 1000
                10000 -> {
                    result += temp * 10000
                    temp = 0
                }
                else -> temp += num
            }
        }
        
        result += temp
        return result
    }
    

    

    


    /**
     * 提取分类
     * @param text 文本
     * @param type 收支类型
     * @return 分类
     */
    private fun extractCategory(text: String, type: String): String {
        val lowerText = text.toLowerCase()
        
        try {
            // 使用HanLP分词识别分类关键词
            val terms = HanLP.newSegment().seg(text)
            val words = terms.map { it.word }
            
            when (type) {
                "income" -> {
                    when {
                        words.any { it in listOf("工资", "奖金", "稿费", "收入", "发", "赚") } -> return "工资"
                        words.any { it in listOf("红包") } -> return "红包"
                        words.any { it in listOf("投资", "理财", "股票", "基金") } -> return "投资"
                        else -> return "其他收入"
                    }
                }
                "expense" -> {
                    when {
                        words.any { it in listOf("吃饭", "饭", "餐", "奶茶", "外卖", "餐厅", "餐饮", "食堂", "饭店", "餐馆", "小吃", "零食", "饮料", "咖啡") } || 
                        lowerText.contains("吃") || lowerText.contains("喝") || lowerText.contains("餐") || lowerText.contains("饭") -> return "餐饮"
                        words.any { it in listOf("打车", "地铁", "公交", "加油", "交通", "汽车", "出行", "开车", "骑车", "步行", "自行车") } || 
                        lowerText.contains("车") || lowerText.contains("油") -> return "交通"
                        words.any { it in listOf("购物", "衣服", "鞋", "买", "商场", "超市", "淘宝", "京东", "网购") } -> return "购物"
                        words.any { it in listOf("娱乐", "玩", "游戏", "电影", "旅游", "旅游", "KTV", "聚会", "派对") } -> return "娱乐"
                        words.any { it in listOf("医疗", "药", "医院", "看病", "医生", "药店") } -> return "医疗"
                        words.any { it in listOf("教育", "学", "学校", "培训", "学习", "课程", "学费", "书本") } -> return "教育"
                        words.any { it in listOf("话费", "流量", "通讯", "通信", "手机", "电话") } -> return "通信"
                        words.any { it in listOf("房租", "水电", "物业", "居家", "装修", "家具") } -> return "居家"
                        words.any { it in listOf("转账", "汇款", "借钱", "还钱") } -> return "转账"
                        else -> return "其他支出"
                    }
                }
                else -> return "其他"
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取分类失败", e)
            // 发生异常时，使用原来的关键词匹配方法
            when (type) {
                "income" -> {
                    when {
                        lowerText.contains("工资") || lowerText.contains("奖金") || lowerText.contains("收入") -> return "工资"
                        lowerText.contains("红包") -> return "红包"
                        lowerText.contains("投资") -> return "投资"
                        else -> return "其他收入"
                    }
                }
                "expense" -> {
                    when {
                        lowerText.contains("吃") || lowerText.contains("喝") || lowerText.contains("餐") || lowerText.contains("饭") -> return "餐饮"
                        lowerText.contains("交通") || lowerText.contains("车") || lowerText.contains("油") -> return "交通"
                        lowerText.contains("购物") || lowerText.contains("买") -> return "购物"
                        lowerText.contains("娱乐") || lowerText.contains("玩") -> return "娱乐"
                        lowerText.contains("医疗") || lowerText.contains("药") -> return "医疗"
                        lowerText.contains("教育") || lowerText.contains("学") -> return "教育"
                        else -> return "其他支出"
                    }
                }
                else -> return "其他"
            }
        }
    }

    /**
     * 提取日期和时间
     * @param text 文本
     * @return 日期和时间
     */
    private fun extractDateTime(text: String): Pair<String, String> {
        val lowerText = text.toLowerCase()
        val calendar = java.util.Calendar.getInstance()
        val time = "00:00"
        
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        var date = dateFormat.format(calendar.time)
        
        try {
            // 使用HanLP分词识别时间词
            val terms = HanLP.newSegment().seg(text)
            
            for (term in terms) {
                val word = term.word
                when (word) {
                    "今天" -> {
                        date = dateFormat.format(calendar.time)
                    }
                    "昨天" -> {
                        calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                        date = dateFormat.format(calendar.time)
                    }
                    "前天" -> {
                        calendar.add(java.util.Calendar.DAY_OF_YEAR, -2)
                        date = dateFormat.format(calendar.time)
                    }
                    "本周" -> {
                        // 本周的第一天（周一）
                        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                        val daysToMonday = if (dayOfWeek == 1) 6 else dayOfWeek - 2
                        calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysToMonday)
                        date = dateFormat.format(calendar.time)
                    }
                    "上周" -> {
                        // 上周的第一天（周一）
                        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
                        val daysToMonday = if (dayOfWeek == 1) 6 else dayOfWeek - 2
                        calendar.add(java.util.Calendar.DAY_OF_YEAR, -daysToMonday - 7)
                        date = dateFormat.format(calendar.time)
                    }
                    "上月" -> {
                        calendar.add(java.util.Calendar.MONTH, -1)
                        date = dateFormat.format(calendar.time)
                    }
                    "本月" -> {
                        // 本月的第一天
                        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
                        date = dateFormat.format(calendar.time)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取日期失败", e)
            // 发生异常时，使用原来的关键词匹配方法
            date = when {
                lowerText.contains("今天") -> dateFormat.format(calendar.time)
                lowerText.contains("昨天") -> {
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
                    dateFormat.format(calendar.time)
                }
                lowerText.contains("前天") -> {
                    calendar.add(java.util.Calendar.DAY_OF_YEAR, -2)
                    dateFormat.format(calendar.time)
                }
                else -> dateFormat.format(calendar.time)
            }
        }
        
        return Pair(date, time)
    }

    /**
     * 提取备注
     * @param text 文本
     * @param amount 金额
     * @param category 分类
     * @return 备注
     */
    private fun extractNote(text: String, amount: Double, category: String): String {
        // 定义分类关键词列表
        val categoryKeywords = mapOf(
            "餐饮" to listOf("吃饭", "饭", "餐", "奶茶", "外卖", "餐厅", "餐饮", "食堂", "饭店", "餐馆", "小吃", "零食", "饮料", "咖啡"),
            "交通" to listOf("打车", "地铁", "公交", "加油", "交通", "汽车", "出行", "开车", "骑车", "步行", "自行车"),
            "购物" to listOf("购物", "衣服", "鞋", "买", "商场", "超市", "淘宝", "京东", "网购"),
            "娱乐" to listOf("娱乐", "玩", "游戏", "电影", "旅游", "KTV", "聚会", "派对"),
            "医疗" to listOf("医疗", "药", "医院", "看病", "医生", "药店"),
            "教育" to listOf("教育", "学", "学校", "培训", "学习", "课程", "学费", "书本"),
            "工资" to listOf("工资", "奖金", "稿费", "收入", "发", "赚"),
            "红包" to listOf("红包"),
            "投资" to listOf("投资", "理财", "股票", "基金")
        )
        
        // 遍历分类关键词，匹配到具体的关键词时，用这个关键词作为备注
        for ((cat, keywords) in categoryKeywords) {
            for (keyword in keywords) {
                if (text.contains(keyword)) {
                    // 特殊处理：如果关键词是"买"，取后面的内容
                    if (keyword == "买") {
                        val index = text.indexOf("买")
                        if (index < text.length - 1) {
                            val afterBuy = text.substring(index + 1)
                            // 提取"买"后面的内容，直到遇到其他关键词
                            val endIndex = afterBuy.indexOfFirst { c -> !Character.isLetter(c) }
                            if (endIndex > 0) {
                                return afterBuy.substring(0, endIndex).trim().replace("\"", "\\\"")
                            }
                        }
                    } else {
                        return keyword.replace("\"", "\\\"")
                    }
                }
            }
        }
        
        // 如果没有匹配到分类关键词，使用精简后的内容
        var note = text
        
        // 移除阿拉伯数字金额相关内容
        val arabicPattern = Pattern.compile("\\d+(\\.\\d+)?(元|块|钱|角|分)")
        note = arabicPattern.matcher(note).replaceAll("")
        
        // 移除中文数字金额相关内容
        val chinesePattern = Pattern.compile("(一|二|两|三|四|五|六|七|八|九|十|百|千|万)+(元|块|钱|角|分)")
        note = chinesePattern.matcher(note).replaceAll("")
        
        // 移除时间相关关键词
        val timeKeywords = arrayOf("今天", "昨天", "前天", "早上", "中午", "晚上")
        for (keyword in timeKeywords) {
            note = note.replace(keyword, "")
        }
        
        // 移除收支类型关键词
        val typeKeywords = arrayOf("收入", "支出", "花", "赚")
        for (keyword in typeKeywords) {
            note = note.replace(keyword, "")
        }
        
        // 去除末尾的"了"和开头的"买"
        note = note.trim()
        if (note.endsWith("了")) {
            note = note.substring(0, note.length - 1)
        }
        if (note.startsWith("买")) {
            note = note.substring(1)
        }
        
        // 处理双引号，避免JSON格式错误
        return note.trim().replace("\"", "\\\"")
    }

    /**
     * 释放资源
     */
    fun release() {
        executor.execute {
            try {
                // 重置初始化状态
                isInitializing = false
                isInitialized = false
                
                // 关闭线程池
                executor.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "释放资源失败", e)
            } finally {
                // 确保单例实例被清空
                synchronized(LocalLLMManager::class.java) {
                    instance = null
                }
            }
        }
    }

    companion object {
        // 单例实例
        private var instance: LocalLLMManager? = null

        /**
         * 获取单例实例
         * @param context 上下文
         * @return LocalLLMManager实例
         */
        fun getInstance(context: Context): LocalLLMManager {
            synchronized(LocalLLMManager::class.java) {
                if (instance == null) {
                    instance = LocalLLMManager(context.applicationContext)
                }
                return instance!!
            }
        }
    }
}