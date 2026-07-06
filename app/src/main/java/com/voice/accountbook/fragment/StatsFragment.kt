package com.voice.accountbook.fragment

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import android.text.Layout
import android.util.TypedValue
import android.widget.PopupWindow
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.viewmodel.CategoryStat
import com.voice.accountbook.viewmodel.DailyTrend
import com.voice.accountbook.util.CategoryIconHelper
import com.voice.accountbook.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * 统计页面 - 深紫磨砂玻璃风格
 * 包含趋势折线图与分类占比环形图
 */
class StatsFragment : Fragment() {

    private lateinit var homeViewModel: HomeViewModel
    private lateinit var lineChartView: LineChartView
    private lateinit var donutChartView: DonutChartView
    private var currentTimeMode = "month"
    private var currentTypeTab = 0 // 0=expense, 1=income

    // 趋势图标题
    private lateinit var tvTrendTitle: TextView
    // 类型切换Tab
    private lateinit var tvTypeExpense: TextView
    private lateinit var tvTypeIncome: TextView
    private lateinit var vTypeIndicator: View
    // 时间切换
    private lateinit var tvTimeWeek: TextView
    private lateinit var tvTimeMonth: TextView
    private lateinit var tvTimeYear: TextView
    // 概览数据
    private lateinit var tvOverviewIncome: TextView
    private lateinit var tvOverviewExpense: TextView
    private lateinit var tvOverviewBalance: TextView
    private lateinit var tvOverviewDailyAvg: TextView
    // 分类标题
    private lateinit var tvCategoryTitle: TextView
    private var tooltipPopup: PopupWindow? = null

    // MoM对比追踪
    private var prevMonthlyExpense = 0.0
    // 分类展开标志
    private var showAllCategories = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(
            com.voice.accountbook.R.layout.fragment_stats, container, false
        )
        initViews(root)
        initViewModel()
        initChartViews(root)
        setupListeners()
        setupChartTouchListener()
        return root
    }

    private fun initViews(root: View) {
        tvTypeExpense = root.findViewById(com.voice.accountbook.R.id.tv_type_expense)
        tvTypeIncome = root.findViewById(com.voice.accountbook.R.id.tv_type_income)
        vTypeIndicator = root.findViewById(com.voice.accountbook.R.id.v_type_indicator)
        tvTimeWeek = root.findViewById(com.voice.accountbook.R.id.tv_time_week)
        tvTimeMonth = root.findViewById(com.voice.accountbook.R.id.tv_time_month)
        tvTimeYear = root.findViewById(com.voice.accountbook.R.id.tv_time_year)
        tvOverviewIncome = root.findViewById(com.voice.accountbook.R.id.tv_overview_income)
        tvOverviewExpense = root.findViewById(com.voice.accountbook.R.id.tv_overview_expense)
        tvOverviewBalance = root.findViewById(com.voice.accountbook.R.id.tv_overview_balance)
        tvOverviewDailyAvg = root.findViewById(com.voice.accountbook.R.id.tv_overview_daily_avg)
        tvCategoryTitle = root.findViewById(com.voice.accountbook.R.id.tv_category_title)
    }

    private fun initViewModel() {
        val db = AccountDatabase.getInstance(requireContext())
        homeViewModel = ViewModelProvider(
            requireActivity(),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(db) as T
                }
            }
        )[HomeViewModel::class.java]
        // 默认月模式
        homeViewModel.setStatsTimeMode("month")
    }

    private fun initChartViews(root: View) {
        val flLineChart = root.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_line_chart)
        lineChartView = LineChartView(requireContext())
        flLineChart.addView(lineChartView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val flDonut = root.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_donut_chart)
        donutChartView = DonutChartView(requireContext())
        flDonut.addView(donutChartView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun setupListeners() {
        tvTimeWeek.setOnClickListener { switchTimeMode("week") }
        tvTimeMonth.setOnClickListener { switchTimeMode("month") }
        tvTimeYear.setOnClickListener { switchTimeMode("year") }
        tvTypeExpense.setOnClickListener { switchTypeTab(0) }
        tvTypeIncome.setOnClickListener { switchTypeTab(1) }
    }

    private fun switchTimeMode(mode: String) {
        if (currentTimeMode == mode) return
        currentTimeMode = mode
        // 淡出动画
        view?.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_line_chart)?.alpha = 0.5f
        view?.findViewById<LinearLayout>(com.voice.accountbook.R.id.ll_ranking)?.alpha = 0.5f
        view?.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_donut_chart)?.alpha = 0.5f
        homeViewModel.setStatsTimeMode(mode)
        updateTimeSwitcherUI(mode)
    }

    private fun switchTypeTab(type: Int) {
        if (currentTypeTab == type) return
        currentTypeTab = type
        homeViewModel.setStatsIncomeType(type)
        updateTypeTabUI(type)
    }

    private fun updateTimeSwitcherUI(mode: String) {
        val selBg = "bg_budget_tip"
        val selColor = Color.parseColor("#FFFFFF")
        val unColor = Color.parseColor("#C8BDE6")
        val unBg = android.R.color.transparent

        fun style(view: TextView, bg: Any, color: Int) {
            val resId = if (bg is String) {
                resources.getIdentifier(bg, "drawable", requireContext().packageName)
            } else bg as Int
            view.background = if (resId != 0) resources.getDrawable(resId, null)
            else null
            view.setTextColor(color)
        }
        when (mode) {
            "week" -> {
                style(tvTimeWeek, selBg, selColor)
                style(tvTimeMonth, unBg, unColor)
                style(tvTimeYear, unBg, unColor)
            }
            "month" -> {
                style(tvTimeWeek, unBg, unColor)
                style(tvTimeMonth, selBg, selColor)
                style(tvTimeYear, unBg, unColor)
            }
            "year" -> {
                style(tvTimeWeek, unBg, unColor)
                style(tvTimeMonth, unBg, unColor)
                style(tvTimeYear, selBg, selColor)
            }
        }
    }

    private fun updateTypeTabUI(type: Int) {
        if (type == 1) {
            tvTypeExpense.setTextColor(Color.parseColor("#8E82B5"))
            tvTypeExpense.paint.isFakeBoldText = false
            tvTypeIncome.setTextColor(Color.parseColor("#FFFFFF"))
            tvTypeIncome.paint.isFakeBoldText = true
            // 指示线移到右侧
            val params = vTypeIndicator.layoutParams as LinearLayout.LayoutParams
            params.width = (resources.displayMetrics.widthPixels - dpInt(48)) / 2
            vTypeIndicator.layoutParams = params
            vTypeIndicator.translationX = params.width.toFloat()
            tvCategoryTitle.text = "收入分类"
        } else {
            tvTypeExpense.setTextColor(Color.parseColor("#FFFFFF"))
            tvTypeExpense.paint.isFakeBoldText = true
            tvTypeIncome.setTextColor(Color.parseColor("#8E82B5"))
            tvTypeIncome.paint.isFakeBoldText = false
            vTypeIndicator.translationX = 0f
            tvCategoryTitle.text = "支出分类"
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    homeViewModel.monthlyIncome.collect { v ->
                        tvOverviewIncome.text = "¥${HomeViewModel.formatAmount(v)}"
                    }
                }
                launch {
                    homeViewModel.monthlyExpense.collect { v ->
                        val baseText = "¥${HomeViewModel.formatAmount(v)}"
                        val momText = if (prevMonthlyExpense > 0) {
                            val diff = v - prevMonthlyExpense
                            val diffStr = "${if (diff >= 0) "+" else ""}¥${HomeViewModel.formatAmount(diff)}"
                            " ($diffStr)"
                        } else ""
                        tvOverviewExpense.text = "$baseText$momText"
                        tvOverviewExpense.setTextColor(Color.parseColor("#F87171"))
                        prevMonthlyExpense = v
                    }
                }
                launch {
                    homeViewModel.monthlyBalance.collect { v ->
                        tvOverviewBalance.text = "¥${HomeViewModel.formatAmount(v)}"
                        val color = if (v >= 0) Color.parseColor("#FFFFFF") else Color.parseColor("#F87171")
                        tvOverviewBalance.setTextColor(color)
                    }
                }
                launch {
                    homeViewModel.dailyAverageExpense.collect { v ->
                        tvOverviewDailyAvg.text = "¥${HomeViewModel.formatAmount(v)}"
                    }
                }
                launch {
                    homeViewModel.dailyTrend.collect { trend ->
                        lineChartView.updateData(trend)
                        lineChartView.showIncome = (currentTypeTab != 1)
                        lineChartView.showExpense = (currentTypeTab != 0)
                        lineChartView.invalidate()
                        view?.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_line_chart)?.animate()?.alpha(1f)?.setDuration(200)?.start()
                    }
                }
                launch {
                    homeViewModel.categoryRanking.collect { ranking ->
                        donutChartView.updateCategories(ranking)
                        updateRankingList(ranking)
                        view?.findViewById<FrameLayout>(com.voice.accountbook.R.id.fl_donut_chart)?.animate()?.alpha(1f)?.setDuration(200)?.start()
                        view?.findViewById<LinearLayout>(com.voice.accountbook.R.id.ll_ranking)?.animate()?.alpha(1f)?.setDuration(200)?.start()
                    }
                }
            }
        }
    }

    private fun updateRankingList(ranking: List<CategoryStat>) {
        val container = view?.findViewById<LinearLayout>(
            com.voice.accountbook.R.id.ll_ranking
        ) ?: return
        val noDataHint = view?.findViewById<TextView>(
            com.voice.accountbook.R.id.tv_no_category_data
        ) ?: return

        // 清除旧的排行条目
        for (i in container.childCount - 1 downTo 1) {
            val child = container.getChildAt(i)
            if (child.id != com.voice.accountbook.R.id.tv_no_category_data) {
                container.removeViewAt(i)
            }
        }

        if (ranking.isEmpty()) {
            noDataHint.visibility = View.VISIBLE
            return
        }
        noDataHint.visibility = View.GONE

        val catColors = listOf("#9F7AFF", "#34D399", "#F87171", "#F59E0B", "#60A5FA", "#F472B6", "#34D3C0", "#A78BFA")
        val displayStats = if (showAllCategories) ranking else ranking.take(3)

        for ((idx, stat) in displayStats.withIndex()) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpInt(40)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    com.voice.accountbook.fragment.BillFragment.filterCategory = stat.category
                    com.voice.accountbook.fragment.BillFragment.filterType = currentTypeTab
                    (requireActivity() as? com.voice.accountbook.MainActivity)?.switchToBillTab()
                }
            }

            val icon = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpInt(24), dpInt(24)).apply { marginEnd = dpInt(10) }
                setImageResource(CategoryIconHelper.getIconResId(stat.category))
                setColorFilter(Color.parseColor("#C8BDE6"))
            }
            row.addView(icon)

            val name = TextView(requireContext()).apply {
                text = stat.category
                textSize = 13f
                setTextColor(Color.parseColor("#C8BDE6"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(name)

            val amount = TextView(requireContext()).apply {
                text = "¥${HomeViewModel.formatAmount(stat.amount)}"
                textSize = 13f
                setTextColor(Color.parseColor("#C8BDE6"))
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(amount)

            // 进度条
            val barFill = ClipDrawable(
                ColorDrawable(Color.parseColor(catColors[idx % catColors.size])),
                Gravity.START,
                ClipDrawable.HORIZONTAL
            )
            val bar = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, dpInt(4), 1f).apply { marginStart = dpInt(12) }
                background = LayerDrawable(arrayOf(
                    ColorDrawable(Color.parseColor("#1AFFFFFF")),
                    barFill
                ))
            }
            barFill.level = ((stat.percentage / 100f) * 10000).toInt().coerceIn(0, 10000)
            row.addView(bar)
            container.addView(row)
        }

        // 查看全部分类 / 收起 切换行
        if (ranking.size > 3) {
            val moreRow = TextView(requireContext()).apply {
                text = if (showAllCategories) "收起 ▲" else "查看全部分类 ›"
                textSize = 12f
                setTextColor(Color.parseColor("#8E82B5"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpInt(36)
                ).apply { topMargin = dpInt(8) }
                setOnClickListener {
                    showAllCategories = !showAllCategories
                    updateRankingList(ranking)
                }
            }
            container.addView(moreRow)
        }
    }

    // ==================== 图表触摸Tooltip ====================
    private fun setupChartTouchListener() {
        lineChartView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showTooltip(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dismissTooltip()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTooltip(touchX: Float, touchY: Float) {
        val data = lineChartView.data
        if (data.isEmpty()) return
        dismissTooltip()

        val w = lineChartView.width.toFloat()
        val padding = dp(32f)
        val chartLeft = padding
        val chartRight = w - dp(8f)
        val step = (chartRight - chartLeft) / (data.size - 1).coerceAtLeast(1)

        val idx = ((touchX - chartLeft) / step).toInt().coerceIn(0, data.size - 1)
        val d = data[idx]

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpInt(12), dpInt(10), dpInt(12), dpInt(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1B1130"))
                cornerRadius = dp(12f)
                setStroke(dpInt(1), Color.parseColor("#33FFFFFF"))
            }
        }

        val dateTv = TextView(requireContext()).apply {
            text = d.dateLabel
            textSize = 13f
            setTextColor(Color.parseColor("#C8BDE6"))
        }
        content.addView(dateTv)

        val incomeTv = TextView(requireContext()).apply {
            text = "收入: ¥${HomeViewModel.formatAmount(d.income)}"
            textSize = 13f
            setTextColor(Color.parseColor("#34D399"))
        }
        content.addView(incomeTv)

        val expenseTv = TextView(requireContext()).apply {
            text = "支出: ¥${HomeViewModel.formatAmount(d.expense)}"
            textSize = 13f
            setTextColor(Color.parseColor("#F87171"))
        }
        content.addView(expenseTv)

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = content.measuredWidth
        val ph = content.measuredHeight

        val location = IntArray(2)
        lineChartView.getLocationOnScreen(location)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        var px = location[0] + touchX.toInt() - pw / 2
        var py = location[1] + touchY.toInt() - ph - dpInt(16)

        if (px < 0) px = dpInt(8)
        if (px + pw > screenW) px = screenW - pw - dpInt(8)
        if (py < 0) py = location[1] + touchY.toInt() + dpInt(16)
        if (py + ph > screenH) py = screenH - ph - dpInt(8)

        tooltipPopup = PopupWindow(content, pw, ph, false).apply {
            isOutsideTouchable = true
            isFocusable = false
            showAtLocation(lineChartView, Gravity.NO_GRAVITY, px, py)
        }
    }

    private fun dismissTooltip() {
        tooltipPopup?.dismiss()
        tooltipPopup = null
    }

    // ==================== 自定义折线图View ====================
    private inner class LineChartView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val fillPath = Path()
        var data: List<DailyTrend> = emptyList()
        var showIncome = true
        var showExpense = true
        private var maxValue = 0.0
        private val padding = dp(32f)
        private val bottomPadding = dp(36f)

        fun updateData(trend: List<DailyTrend>) {
            this.data = trend
            maxValue = trend.flatMap { listOf(it.income, it.expense) }.maxOrNull() ?: 100.0
            if (maxValue == 0.0) maxValue = 100.0
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0 || data.isEmpty()) return

            val w = width.toFloat()
            val h = height.toFloat()
            val chartLeft = padding
            val chartRight = w - dp(8f)
            val chartTop = dp(8f)
            val chartBottom = h - bottomPadding
            val chartH = chartBottom - chartTop

            // 水平辅助线（半透灰）
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.parseColor("#14FFFFFF")
            for (i in 0..3) {
                val y = chartTop + chartH * i / 4f
                canvas.drawLine(chartLeft, y, chartRight, y, paint)
            }

            // 绘制曲线
            val allZeroExpense = data.all { it.expense == 0.0 }
            if (showExpense) drawLine(canvas, chartLeft, chartRight, chartTop, chartH,
                Color.parseColor("#F87171"), Color.parseColor("#66F87171"),
                Color.parseColor("#00F87171"), { it.expense }, allZeroExpense)
            val allZeroIncome = data.all { it.income == 0.0 }
            if (showIncome) drawLine(canvas, chartLeft, chartRight, chartTop, chartH,
                Color.parseColor("#34D399"), Color.parseColor("#6634D399"),
                Color.parseColor("#0034D399"), { it.income }, allZeroIncome)

            // X轴标签
            paint.style = Paint.Style.FILL
            paint.textSize = sp(11f)
            paint.color = Color.parseColor("#8E82B5")
            paint.textAlign = Paint.Align.CENTER
            val step = (chartRight - chartLeft) / (data.size - 1).coerceAtLeast(1)
            for ((i, d) in data.withIndex()) {
                val x = chartLeft + step * i
                // 控制标签密度
                val show = when {
                    data.size <= 7 -> true
                    data.size <= 31 -> i == 0 || i == 7 || i == 14 || i == 21 || i == data.size - 1
                    else -> true
                }
                if (show) {
                    canvas.drawText(d.dateLabel, x, h - dp(8f), paint)
                }
            }
        }

        private fun drawLine(
            canvas: Canvas,
            left: Float, right: Float,
            top: Float, chartH: Float,
            lineColor: Int, fillTopColor: Int, fillBottomColor: Int,
            valueFn: (DailyTrend) -> Double,
            skipFill: Boolean = false
        ) {
            val step = (right - left) / (data.size - 1).coerceAtLeast(1)
            path.reset()
            fillPath.reset()

            for ((i, d) in data.withIndex()) {
                val x = left + step * i
                val v = valueFn(d)
                val y = top + chartH * (1 - (v / maxValue).toFloat()).coerceIn(0f, 1f)
                if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, y) }
                else {
                    // 三次贝塞尔平滑
                    val prevV = valueFn(data[i - 1])
                    val prevY = top + chartH * (1 - (prevV / maxValue).toFloat()).coerceIn(0f, 1f)
                    val prevX = left + step * (i - 1)
                    val cx1 = prevX + (x - prevX) / 3f
                    val cy1 = prevY
                    val cx2 = prevX + (x - prevX) * 2f / 3f
                    val cy2 = y
                    path.cubicTo(cx1, cy1, cx2, cy2, x, y)
                    fillPath.cubicTo(cx1, cy1, cx2, cy2, x, y)
                }
            }

            // 渐变填充
            if (!skipFill) {
                fillPath.lineTo(right, top + chartH)
                fillPath.lineTo(left, top + chartH)
                fillPath.close()
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    0f, top, 0f, top + chartH,
                    fillTopColor, fillBottomColor, Shader.TileMode.CLAMP
                )
                canvas.drawPath(fillPath, paint)
                paint.shader = null
            }

            // 曲线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = lineColor
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
        }
    }

    // ==================== 自定义甜甜圈图View ====================
    private inner class DonutChartView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rectF = RectF()
        private var categories: List<CategoryStat> = emptyList()
        private var total: Double = 0.0
        private val colors = listOf("#9F7AFF", "#34D399", "#F87171", "#60A5FA", "#FBBF24", "#A78BFA", "#FB923C", "#4ADE80")
        private val strokeW = dp(8f)

        fun updateCategories(cats: List<CategoryStat>) {
            this.categories = cats
            this.total = cats.sumOf { it.amount }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return
            val cx = width / 2f
            val cy = height / 2f
            val r = (Math.min(width, height) / 2f - strokeW / 2f) * 0.78f

            // 背景圆环
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeW
            paint.color = Color.parseColor("#332A1C46")
            paint.strokeCap = Paint.Cap.BUTT
            canvas.drawCircle(cx, cy, r, paint)
            paint.strokeCap = Paint.Cap.ROUND

            // 分类弧段
            var startAngle = -90f
            for ((i, cat) in categories.withIndex()) {
                if (total <= 0 || cat.amount <= 0) continue
                val sweep = (cat.amount / total * 360f).toFloat()
                paint.color = Color.parseColor(colors[i % colors.size])

                // 弧之间留微小间距
                val gap = 2f
                val adjustedSweep = if (categories.size > 1) (sweep - gap).coerceAtLeast(1f) else sweep
                rectF.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rectF, startAngle + gap / 2f, adjustedSweep, false, paint)
                startAngle += sweep
            }

            // 中心文字
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER

            paint.textSize = sp(18f)
            paint.color = Color.WHITE
            paint.isFakeBoldText = true
            canvas.drawText("¥${HomeViewModel.formatAmount(total)}", cx, cy + sp(6f), paint)
            paint.isFakeBoldText = false

            paint.textSize = sp(11f)
            paint.color = Color.parseColor("#8E82B5")
            val label = if (currentTypeTab == 0) "总支出" else "总收入"
            canvas.drawText(label, cx, cy - sp(16f), paint)
        }
    }

    // dp() 返回Float用于Canvas绘制，dpInt()返回Int用于布局参数
    private fun dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
    private fun dpInt(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
    private fun sp(v: Number): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics
    )
}