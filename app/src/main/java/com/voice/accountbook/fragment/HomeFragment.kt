package com.voice.accountbook.fragment

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voice.accountbook.R
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.databinding.FragmentHomeBinding
import com.voice.accountbook.entity.AccountBean
import com.voice.accountbook.utils.LocalLLMManager
import com.voice.accountbook.utils.VoskSpeechRecognizer
import com.voice.accountbook.viewmodel.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 首页Fragment - 深色炫酷磨砂玻璃风格
 * 保留全部原有业务逻辑：语音识别、语义解析、数据库存储
 * 新增：统一ViewModel数据源、环形预算图、最近账单、按钮动画
 */
class HomeFragment : Fragment() {

    private lateinit var binding: FragmentHomeBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // 语音识别 & 语义解析
    private val voskRecognizer by lazy { VoskSpeechRecognizer.getInstance(requireContext()) }
    private val llmManager by lazy { LocalLLMManager.getInstance(requireContext()) }

    // 录音状态
    private var isRecording = false
    @Volatile private var isVoskReady = false
    @Volatile private var isLLMReady = false
    @Volatile private var isModelsInitializing = false
    private var modelLoadTimeoutHandler: Handler? = null

    // 数据库
    private val db by lazy { AccountDatabase.getInstance(requireContext()) }
    private val accountDao by lazy { db.accountDao() }

    // 统一ViewModel
    private lateinit var homeViewModel: HomeViewModel

    // 环形图表
    private lateinit var budgetRingView: BudgetRingView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化ViewModel（统一数据源）
        homeViewModel = ViewModelProvider(requireActivity(),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(db) as T
                }
            }
        )[HomeViewModel::class.java]

        initPermission()
        initBudgetRing()
        observeViewModel()

        // 页面加载交错卡片入场动画
        view.post { animateCardEntrance() }

        binding.tvRecognition.text = "欢迎使用语音记账"

        initModels()

        // 按钮事件
        binding.fabRecord.setOnClickListener {
            animateButtonPress(binding.fabRecord)
            if (isRecording) stopRecording() else startRecording()
        }
        binding.fabManual.setOnClickListener {
            animateButtonPress(binding.fabManual)
            showAccountFormDialog(false, null)
        }
        binding.btnRetry.setOnClickListener {
            if (!isModelsInitializing) {
                isVoskReady = false; isLLMReady = false
                initModels()
            }
        }

        // 查看全部 -> 切换到账单页
        binding.tvViewAll.setOnClickListener {
            try {
                val mainActivity = requireActivity() as com.voice.accountbook.MainActivity
                mainActivity.switchToBillTab()
            } catch (e: Exception) {
                Log.e("HomeFragment", "跳转账单页失败", e)
            }
        }

        // 点击预算提示条 -> 设置今日剩余金额
        binding.llBudgetTip.setOnClickListener {
            showSetBudgetDialog()
        }
    }

    // ========== ViewModel 数据观察 ==========

    private fun observeViewModel() {
        // 月份切换：上一月
        binding.ivMonthPrev.setOnClickListener {
            animateButtonPress(binding.ivMonthPrev)
            homeViewModel.navigateMonth(-1)
        }
        // 月份切换：下一月
        binding.ivMonthNext.setOnClickListener {
            animateButtonPress(binding.ivMonthNext)
            homeViewModel.navigateMonth(1)
        }
        // 点击月份标题回到本月
        binding.tvMonthTitle.setOnClickListener {
            animateButtonPress(binding.tvMonthTitle)
            homeViewModel.navigateMonth(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // 月份标题
                launch {
                    homeViewModel.currentYearMonth.collect { ym ->
                        val parts = ym.split("-")
                        val monthName = "${parts[1].toInt()}月"
                        binding.tvMonthTitle.text = monthName
                        // 如果是当前月份禁用下一月按钮
                        val currentYm = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        binding.ivMonthNext.visibility =
                            if (ym == currentYm) View.INVISIBLE else View.VISIBLE
                    }
                }
                // 月份切换 → 淡入刷新动画
                launch {
                    homeViewModel.currentYearMonth.collect {
                        binding.cvStatistics.animate().alpha(0.4f).setDuration(0).start()
                        binding.cvRecentBills.animate().alpha(0.4f).setDuration(0).start()
                        binding.llBudgetTip.animate().alpha(0.4f).setDuration(0).start()
                        binding.cvStatistics.animate().alpha(1f).setDuration(250).start()
                        binding.cvRecentBills.animate().alpha(1f).setDuration(300).start()
                        binding.llBudgetTip.animate().alpha(1f).setDuration(350).start()
                    }
                }
                launch {
                    var prev = 0.0
                    homeViewModel.monthlyIncome.collect { v ->
                        animateNumberChange(binding.tvIncome, "¥", prev, v, false)
                        prev = v
                    }
                }
                launch {
                    var prev = 0.0
                    homeViewModel.monthlyExpense.collect { v ->
                        animateNumberChange(binding.tvExpense, "¥", prev, v, false)
                        prev = v
                        updateBudgetRing()
                    }
                }
                launch {
                    var prev = 0.0
                    homeViewModel.monthlyBalance.collect { v ->
                        animateNumberChange(binding.tvBalance, "¥", prev, v, false)
                        prev = v
                        val color = if (v >= 0) Color.parseColor("#FFFFFF") else Color.parseColor("#F87171")
                        binding.tvBalance.setTextColor(color)
                    }
                }
                // 环形图观察
                launch {
                    homeViewModel.remainingBudget.collect { v ->
                        updateBudgetRing()
                    }
                }
                // 每日可消费观察 → 更新预算提示条（与剩余预算独立计算）
                launch {
                    homeViewModel.dailyRemainingBudget.collect { v ->
                        updateBudgetTip(v)
                    }
                }
                launch {
                    homeViewModel.monthlyBudget.collect { v ->
                        updateBudgetRing()
                        updateBudgetTip(homeViewModel.dailyRemainingBudget.value)
                    }
                }
                launch {
                    homeViewModel.budgetRatio.collect {
                        updateBudgetRing()
                    }
                }
                launch {
                    homeViewModel.recentAccounts.collect { accounts ->
                        updateRecentBills(accounts)
                    }
                }
            }
        }
    }

    /** 更新今日预算提示条（显示今日还可消费，与剩余预算独立计算） */
    private fun updateBudgetTip(dailyRemaining: Double?) {
        if (dailyRemaining == null) {
            binding.tvTodayRemaining.text = "去设置预算"
            binding.tvTodayRemaining.setTextColor(Color.parseColor("#9F7AFF"))
            binding.tvBudgetLabel.text = "点击设置月度预算"
            binding.llBudgetTip.background = resources.getDrawable(R.drawable.bg_budget_tip, null)
        } else {
            val formatted = String.format("%.2f", dailyRemaining)
            binding.tvTodayRemaining.text = "¥$formatted"
            if (dailyRemaining < 0) {
                binding.tvTodayRemaining.setTextColor(Color.parseColor("#F87171"))
                binding.llBudgetTip.setBackgroundColor(Color.parseColor("#33F87171"))
            } else {
                binding.tvTodayRemaining.setTextColor(Color.parseColor("#9F7AFF"))
                binding.llBudgetTip.background = resources.getDrawable(R.drawable.bg_budget_tip, null)
            }
            binding.tvBudgetLabel.text = "今日还可消费"
        }
    }

    // ========== 最近账单列表更新 ==========

    private fun updateRecentBills(accounts: List<AccountBean>) {
        val hasItems = accounts.isNotEmpty()
        binding.tvNoBillsHint.visibility = if (hasItems) View.GONE else View.VISIBLE

        // 辅助方法：设置单条账单
        fun setBillItem(
            itemView: View, icon: ImageView, categoryTv: TextView,
            noteTv: TextView, amountTv: TextView, divider: View,
            account: AccountBean?, show: Boolean
        ) {
            if (show && account != null) {
                itemView.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                setCategoryIcon(icon, account.category)
                categoryTv.text = account.category
                noteTv.text = account.remark ?: ""
                if (account.type == 0) {
                    amountTv.text = "-¥${HomeViewModel.formatAmount(account.money)}"
                    amountTv.setTextColor(Color.parseColor("#F87171"))
                } else {
                    amountTv.text = "+¥${HomeViewModel.formatAmount(account.money)}"
                    amountTv.setTextColor(Color.parseColor("#34D399"))
                }
            } else {
                itemView.visibility = View.GONE
                divider.visibility = View.GONE
            }
        }

        setBillItem(
            binding.llBillItem1, binding.ivBillIcon1, binding.tvBillCategory1,
            binding.tvBillNote1, binding.tvBillAmount1, binding.vDivider1,
            accounts.getOrNull(0), accounts.size >= 1
        )
        setBillItem(
            binding.llBillItem2, binding.ivBillIcon2, binding.tvBillCategory2,
            binding.tvBillNote2, binding.tvBillAmount2, binding.vDivider2,
            accounts.getOrNull(1), accounts.size >= 2
        )
        setBillItem(
            binding.llBillItem3, binding.ivBillIcon3, binding.tvBillCategory3,
            binding.tvBillNote3, binding.tvBillAmount3, View(context),
            accounts.getOrNull(2), accounts.size >= 3
        )

        // 点击账单项→编辑弹窗
        binding.llBillItem1.setOnClickListener { accounts.getOrNull(0)?.let { showEditBillDialog(it) } }
        binding.llBillItem2.setOnClickListener { accounts.getOrNull(1)?.let { showEditBillDialog(it) } }
        binding.llBillItem3.setOnClickListener { accounts.getOrNull(2)?.let { showEditBillDialog(it) } }
    }

    private fun setCategoryIcon(iv: ImageView, category: String) {
        iv.setImageResource(com.voice.accountbook.util.CategoryIconHelper.getIconResId(category))
    }

    // ========== 环形预算图表 ==========

    private fun initBudgetRing() {
        binding.vCircleChart.visibility = View.GONE
        val parent = binding.vCircleChart.parent as ViewGroup
        val params = binding.vCircleChart.layoutParams
        budgetRingView = BudgetRingView(requireContext())
        budgetRingView.layoutParams = params
        parent.addView(budgetRingView)
    }

    private fun updateBudgetRing() {
        if (::budgetRingView.isInitialized && ::homeViewModel.isInitialized) {
            budgetRingView.update(
                homeViewModel.monthlyBudget.value,
                homeViewModel.monthlyExpense.value,
                homeViewModel.remainingBudget.value,
                homeViewModel.budgetRatio.value
            )
        }
    }

    /**
     * 环形预算图表View - 深紫磨砂风格
     * 预算与收入完全解耦，存储在独立的budget表
     * budget=null → 显示"去设置预算"引导文案
     * ratio>1.0 → 溢出红色警示
     * remaining允许负数 → 红色显示
     * 300ms ValueAnimator 平滑过渡数值变化
     */
    @SuppressLint("Recycle")
    private class BudgetRingView(
        context: Context
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rectF = RectF()
        private val strokeWidth = dpToPx(6f)
        private val centerX get() = width / 2f
        private val centerY get() = height / 2f
        private val radius get() = (Math.min(width, height) / 2f - strokeWidth / 2) * 0.82f

        private var animBudget: Float? = null
        private var animExpense = 0f
        private var animRatio = 0f
        private var targetBudget: Float? = null
        private var targetExpense = 0f
        private var targetRatio = 0f
        private var remaining: Double? = null
        private var firstDraw = true

        init {
            postDelayed({
                if (targetRatio > 0 && animRatio == 0f) {
                    ValueAnimator.ofFloat(0f, targetRatio).apply {
                        duration = 400
                        interpolator = android.view.animation.DecelerateInterpolator()
                        addUpdateListener { animRatio = it.animatedValue as Float; invalidate() }
                        start()
                    }
                }
                firstDraw = false
            }, 100)
        }

        fun update(budget: Double?, expense: Double, remaining: Double?, ratio: Float) {
            this.remaining = remaining
            val newBudgetTarget = budget?.toFloat()
            this.targetBudget = newBudgetTarget
            this.targetExpense = expense.toFloat()
            this.targetRatio = ratio

            // 首次设置时直接赋值
            if (animBudget == null && newBudgetTarget != null) {
                animBudget = newBudgetTarget
            }
            if (animExpense == 0f && expense > 0) {
                animExpense = expense.toFloat()
            }

            val fromBudget = animBudget
            val toBudget = newBudgetTarget
            val fromExpense = animExpense
            val toExpense = expense.toFloat()
            val fromRatio = animRatio
            val toRatio = ratio

            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener {
                    val frac = animatedFraction
                    animBudget = if (fromBudget != null && toBudget != null) {
                        fromBudget + (toBudget - fromBudget) * frac
                    } else toBudget
                    animExpense = fromExpense + (toExpense - fromExpense) * frac
                    animRatio = fromRatio + (toRatio - fromRatio) * frac
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width == 0 || height == 0) return

            val curBudget = animBudget
            // 未设置预算 → 显示引导文案
            if (curBudget == null || curBudget == 0f) {
                drawNoBudget(canvas)
                return
            }

            // 进度比例：可超过1.0溢出
            val curRatio = animRatio
            val sweepAngle = (curRatio.coerceAtMost(1.1f) * 360f).coerceAtMost(370f)

            // 背景圆环（深紫灰底色）
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = Color.parseColor("#332A1C46")
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawCircle(centerX, centerY, radius, paint)

            // 进度弧
            rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            if (sweepAngle > 0) {
                val gradColors = when {
                    curRatio <= 0.8f -> intArrayOf(Color.parseColor("#F87171"), Color.parseColor("#EF4444"))
                    curRatio <= 1.0f -> intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#F97316"))
                    else -> intArrayOf(Color.parseColor("#DC2626"), Color.parseColor("#EF4444"))
                }
                val gradient = android.graphics.SweepGradient(
                    centerX, centerY, gradColors, floatArrayOf(0f, 1f)
                )
                paint.shader = gradient
                canvas.drawArc(rectF, -90f, sweepAngle, false, paint)
                paint.shader = null
            }

            // 中心文字区
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER

            // "剩余预算"标签
            paint.textSize = spToPx(12f)
            paint.color = Color.parseColor("#8E82B5")
            canvas.drawText("剩余预算", centerX, centerY - spToPx(14f), paint)

            // 剩余金额数字
            val rem = remaining ?: 0.0
            paint.textSize = spToPx(24f)
            paint.color = when {
                curRatio > 1.0f -> Color.parseColor("#DC2626")
                rem < 0 -> Color.parseColor("#F87171")
                else -> Color.WHITE
            }
            paint.isFakeBoldText = true
            canvas.drawText(
                "¥${String.format("%.0f", rem)}",
                centerX, centerY + spToPx(12f), paint
            )
            paint.isFakeBoldText = false
        }

        /** 未设置预算时的绘制 */
        private fun drawNoBudget(canvas: Canvas) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = Color.parseColor("#332A1C46")
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawCircle(centerX, centerY, radius, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = spToPx(13f)
            paint.color = Color.parseColor("#9F7AFF")
            paint.isFakeBoldText = false
            canvas.drawText("去设置预算", centerX, centerY + spToPx(4f), paint)
        }

        private fun dpToPx(dp: Float): Float = dp * context.resources.displayMetrics.density
        private fun spToPx(sp: Float): Float = sp * context.resources.displayMetrics.scaledDensity
    }

    // ========== 月度预算设置（替代旧的今日剩余金额） ==========

    /** 点击预算提示条 → 弹出月度预算设置对话框（玻璃风格） */
    @SuppressLint("ClickableViewAccessibility")
    private fun showSetBudgetDialog() {
        val dialog = Dialog(requireContext(), R.style.GlassDialog)
        val dp = resources.displayMetrics.density
        val dpInt = { v: Float -> (v * dp + 0.5f).toInt() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.bg_dialog, null)
            setPadding(dpInt(24f), dpInt(24f), dpInt(24f), dpInt(24f))
        }

        // 标题
        val title = TextView(requireContext()).apply {
            text = "设置月度预算"
            textSize = 18f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(28f) }
        }
        root.addView(title)

        // ¥前缀 + 金额输入行
        val amountRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(4f) }
        }

        val prefixYuan = TextView(requireContext()).apply {
            text = "¥"
            textSize = 32f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dpInt(6f) }
        }
        amountRow.addView(prefixYuan)

        val input = EditText(requireContext()).apply {
            textSize = 32f
            setTextColor(Color.parseColor("#FFFFFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = null
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "0"
            setHintTextColor(Color.parseColor("#8E82B5"))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            val currentBudget = homeViewModel.monthlyBudget.value
            if (currentBudget != null && currentBudget > 0) setText(currentBudget.toInt().toString())
            // 清除 Material 绿色下划线
            setOnFocusChangeListener { _, _ -> }
        }
        amountRow.addView(input)

        // 底部分隔线
        val bottomLine = View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpInt(1.5f)
            ).apply { bottomMargin = dpInt(28f) }
        }
        root.addView(amountRow)
        root.addView(bottomLine)

        // 焦点切换底部线
        input.setOnFocusChangeListener { _, hasFocus ->
            bottomLine.setBackgroundColor(
                if (hasFocus) Color.parseColor("#9F7AFF") else Color.parseColor("#33FFFFFF")
            )
        }

        // 按钮行
        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpInt(48f)
            )
        }

        // 取消按钮（40%宽，玻璃bg）
        val btnCancel = TextView(requireContext()).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(20f).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 2f
            ).apply { marginEnd = dpInt(12f) }
            setOnClickListener {
                dialog.dismiss()
            }
        }

        // 确定按钮（60%宽，#9F7AFF bg）
        val btnOk = TextView(requireContext()).apply {
            text = "确定"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(20f).toFloat()
                setColor(Color.parseColor("#9F7AFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 3f
            )
            setOnClickListener {
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    val amount = text.toDoubleOrNull()
                    if (amount != null && amount > 0) {
                        homeViewModel.setMonthlyBudget(amount)
                        Toast.makeText(requireContext(), "预算已更新", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnOk)
        root.addView(buttonRow)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes?.windowAnimations = android.R.style.Animation_Dialog
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()

        // 自动弹出键盘
        input.postDelayed({
            input.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    // ========== 权限 ==========

    private fun initPermission() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.RECORD_AUDIO] == true) startRecordingInternal()
            else Toast.makeText(requireContext(), "请授予录音权限以使用语音记账功能", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 模型初始化 ==========

    private fun initModels() {
        if (isModelsInitializing) {
            Toast.makeText(requireContext(), "模型正在初始化中...", Toast.LENGTH_SHORT).show()
            return
        }
        isVoskReady = false; isLLMReady = false; isModelsInitializing = true
        requireActivity().runOnUiThread {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvLoading.visibility = View.VISIBLE
            binding.tvLoading.text = "正在加载模型..."
            binding.btnRetry.visibility = View.GONE
            binding.fabRecord.isEnabled = false
        }
        modelLoadTimeoutHandler = Handler(Looper.getMainLooper())
        modelLoadTimeoutHandler?.postDelayed({
            if (isModelsInitializing) {
                isModelsInitializing = false
                requireActivity().runOnUiThread {
                    hideLoading()
                    binding.fabRecord.isEnabled = false
                    binding.btnRetry.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "模型加载超时，请点击重试", Toast.LENGTH_SHORT).show()
                }
            }
        }, 15000)

        voskRecognizer.initModel(object : VoskSpeechRecognizer.RecognitionCallback {
            override fun onInitSuccess() {
                requireActivity().runOnUiThread { isVoskReady = true; checkAllModelReady() }
            }
            override fun onInitFail(error: String) {
                requireActivity().runOnUiThread { isVoskReady = false; checkAllModelReady() }
            }
            override fun onRecognizing(text: String) {
                requireActivity().runOnUiThread {
                    binding.tvRecognition.text = text
                }
            }
            override fun onRecognizeComplete(text: String) {
                requireActivity().runOnUiThread {
                    binding.tvRecognition.text = text
                    processRecognitionResult(text)
                }
            }
            override fun onError(error: String) {
                requireActivity().runOnUiThread {
                    showLightToast("语音识别出错: $error")
                    binding.fabRecord.setImageResource(R.drawable.ic_mic)
                    isRecording = false
                    stopRecordingAnimation()
                }
            }
            override fun onPermissionDenied() {
                requireActivity().runOnUiThread {
                    showLightToast("请授予录音权限")
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
            }
        })

        llmManager.initModel(object : LocalLLMManager.LLMCallback {
            override fun onLoadProgress(progress: Int) {
                requireActivity().runOnUiThread {
                    binding.tvLoading.text = "正在加载大模型... $progress%"
                }
            }
            override fun onLoadSuccess() {
                requireActivity().runOnUiThread { isLLMReady = true; checkAllModelReady() }
            }
            override fun onLoadFail(error: String) {
                requireActivity().runOnUiThread { isLLMReady = false; checkAllModelReady() }
            }
            override fun onGenerateSuccess(result: String) {
                requireActivity().runOnUiThread { parseLLMResult(result) }
            }
            override fun onGenerateFail(error: String) {
                requireActivity().runOnUiThread {
                    AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                        .setTitle("提示")
                        .setMessage("无法解析您的语音内容，请使用更加标准的用语，例如：\n\n- 今天吃饭花了50元\n- 昨天交通费用30元\n- 收到工资2000元")
                        .setPositiveButton("确定", null)
                        .show()
                    binding.fabRecord.setImageResource(R.drawable.ic_mic)
                    isRecording = false
                    stopRecordingAnimation()
                }
            }
        })
    }

    private fun checkAllModelReady() {
        if (!isVoskReady && !isLLMReady) return
        if ((isVoskReady || !isModelsInitializing) && (isLLMReady || !isModelsInitializing)) {
            isModelsInitializing = false
            modelLoadTimeoutHandler?.removeCallbacksAndMessages(null)
            modelLoadTimeoutHandler = null
            requireActivity().runOnUiThread {
                hideLoading()
                if (isVoskReady && isLLMReady) {
                    binding.fabRecord.isEnabled = true
                    Toast.makeText(requireContext(), "模型加载完成，可以开始语音记账", Toast.LENGTH_SHORT).show()
                    binding.btnRetry.visibility = View.GONE
                } else {
                    binding.fabRecord.isEnabled = false
                    binding.btnRetry.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), "部分模型加载失败，请点击重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
        binding.tvLoading.visibility = View.GONE
    }

    // ========== 录音 ==========

    private fun startRecording() {
        if (requireActivity().checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        } else {
            startRecordingInternal()
        }
    }

    private fun startRecordingInternal() {
        if (!isVoskReady || !isLLMReady) {
            showLightToast("模型尚未加载完成"); return
        }
        voskRecognizer.startRecording()
        isRecording = true
        binding.fabRecord.setImageResource(android.R.drawable.ic_media_pause)
        binding.tvRecognition.text = "正在录音..."
        startRecordingAnimation()
    }

    private fun stopRecording() {
        voskRecognizer.stopRecording()
        isRecording = false
        binding.fabRecord.setImageResource(R.drawable.ic_mic)
        stopRecordingAnimation()
    }

    // ========== 识别处理 ==========

    private fun processRecognitionResult(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "未识别到语音内容", Toast.LENGTH_SHORT).show()
            binding.fabRecord.setImageResource(R.drawable.ic_mic)
            isRecording = false
            return
        }
        llmManager.generate(text)
    }

    private fun parseLLMResult(result: String) {
        try {
            val jsonObject = JSONObject(result)
            val type = jsonObject.getString("type")
            val amount = jsonObject.getDouble("amount")
            val category = jsonObject.getString("category")
            val date = jsonObject.getString("date")
            val time = jsonObject.getString("time")
            val note = if (jsonObject.has("note")) jsonObject.getString("note") else ""
            val accountType = if (type == "expense") 0 else 1

            val accountData = hashMapOf<String, Any>(
                "type" to accountType, "amount" to amount,
                "category" to category, "date" to date, "note" to note
            )
            showAccountFormDialog(true, accountData)
        } catch (e: Exception) {
            Log.e("HomeFragment", "解析结果失败", e)
            AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                .setTitle("提示")
                .setMessage("无法解析您的语音内容，请使用更加标准的用语。")
                .setPositiveButton("确定", null)
                .show()
        } finally {
            binding.fabRecord.setImageResource(R.drawable.ic_mic)
            isRecording = false
            stopRecordingAnimation()
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面恢复时ViewModel会自动刷新数据
    }

    override fun onDestroy() {
        super.onDestroy()
        voskRecognizer.release()
        llmManager.release()
    }

    // ========== 保存记账 ==========

    private fun saveAccountRecord(type: Int, amount: Double, category: String, remark: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val account = AccountBean(
                    money = amount, type = type, category = category,
                    time = System.currentTimeMillis(), remark = remark
                )
                accountDao.insert(account)
                Log.d("HomeFragment", "记账记录保存成功")
                withContext(Dispatchers.Main) {
                    // ViewModel自动通过Flow更新统计数据
                    val today = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    }
            } catch (e: Exception) {
                Log.e("HomeFragment", "保存记账记录失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "保存记账记录失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ========== 记账表单弹窗 ==========

    // ========== 手动记账弹窗（深紫磨砂玻璃风格重写） ==========

    private fun showAccountFormDialog(isVoice: Boolean, accountData: HashMap<String, Any>?) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_account_form, null)

        // —— 视图引用 ——
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val etAmount = dialogView.findViewById<EditText>(R.id.et_amount)
        val tvSegExpense = dialogView.findViewById<TextView>(R.id.tv_segment_expense)
        val tvSegIncome = dialogView.findViewById<TextView>(R.id.tv_segment_income)
        val llCategoryTags = dialogView.findViewById<LinearLayout>(R.id.ll_category_tags)
        val tvDate = dialogView.findViewById<TextView>(R.id.tv_date)
        val llDateRow = dialogView.findViewById<LinearLayout>(R.id.ll_date_row)
        val etRemark = dialogView.findViewById<EditText>(R.id.et_remark)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnSave = dialogView.findViewById<TextView>(R.id.btn_save)

        tvTitle.text = if (isVoice) "语音记账确认" else "记一笔"

        // —— 分类定义 ——
        val expenseCategories = arrayOf("餐饮", "交通", "购物", "娱乐", "医疗", "教育", "通信", "居家", "转账", "其他支出")
        val incomeCategories = arrayOf("工资", "红包", "投资", "其他收入")
        // 分类图标映射
        val categoryIcons = mapOf(
                    "餐饮" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("餐饮"),
                    "交通" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("交通"),
                    "购物" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("购物"),
                    "娱乐" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("娱乐"),
                    "医疗" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("医疗"),
                    "教育" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("教育"),
                    "通信" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("通信"),
                    "居家" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("居家"),
                    "转账" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("转账"),
                    "其他支出" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("其他支出"),
                    "工资" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("工资"),
                    "红包" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("红包"),
                    "投资" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("投资"),
                    "其他收入" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("其他收入")
                )

        // —— 状态 ——
        var currentType = 0  // 0=支出 1=收入
        var selectedCategory = ""  // 当前选中的分类
        val voiceType = accountData?.get("type") as? Int

        // —— 更新分类标签栏 ——
        fun updateCategoryTags() {
            llCategoryTags.removeAllViews()
            val cats = if (currentType == 0) expenseCategories else incomeCategories
            val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }

            // 首次填充时自动选中匹配的分类
            if (selectedCategory.isEmpty() || !cats.contains(selectedCategory)) {
                selectedCategory = cats[0]
            }

            for (cat in cats) {
                val tag = createCategoryTag(cat, cat == selectedCategory, categoryIcons[cat])
                tag.setOnClickListener {
                    selectedCategory = cat
                    updateCategoryTags()
                }
                llCategoryTags.addView(tag)
            }
        }

        // —— 更新类型分段控件样式 ——
        fun updateSegmentUI() {
            val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
            val r = dp(18f).toFloat()

            if (currentType == 0) {
                // 支出选中 → 红色渐变填充+白字
                val expenseBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#F87171"))
                }
                tvSegExpense.background = expenseBg
                tvSegExpense.setTextColor(Color.WHITE)
                tvSegIncome.background = null
                tvSegIncome.setTextColor(Color.parseColor("#C8BDE6"))
            } else {
                // 收入选中 → 绿色渐变填充+白字
                val incomeBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#34D399"))
                }
                tvSegIncome.background = incomeBg
                tvSegIncome.setTextColor(Color.WHITE)
                tvSegExpense.background = null
                tvSegExpense.setTextColor(Color.parseColor("#C8BDE6"))
            }
        }

        // —— 初始化类型 ——
        val initType = voiceType ?: 0
        currentType = initType

        // —— 初始化分类 ——
        val initCats = if (initType == 0) expenseCategories else incomeCategories
        val voiceCategory = accountData?.get("category") as? String
        selectedCategory = if (voiceCategory != null && initCats.contains(voiceCategory)) voiceCategory else initCats[0]
        updateCategoryTags()

        // —— 类型切换事件 ——
        tvSegExpense.setOnClickListener {
            if (currentType != 0) { currentType = 0; updateSegmentUI(); updateCategoryTags() }
        }
        tvSegIncome.setOnClickListener {
            if (currentType != 1) { currentType = 1; updateSegmentUI(); updateCategoryTags() }
        }

        // —— 日期初始化 ——
        val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
        tvDate.text = currentDateStr

        // 日期行点击 → 玻璃日期选择器
        llDateRow.setOnClickListener {
            val cal = Calendar.getInstance()
            showGlassDatePicker(cal) { y, m, d ->
                tvDate.text = String.format("%04d-%02d-%02d", y, m + 1, d)
            }
        }

        // —— 语音数据预填 ——
        accountData?.let {
            val amt = it["amount"] as Double
            etAmount.setText(if (amt == amt.toLong().toDouble()) amt.toLong().toString() else amt.toString())
            tvDate.text = it["date"] as String
            etRemark.setText(it["note"] as String)
        }

        // —— 金额格式化（失去焦点时保留两位小数） ——
        etAmount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = etAmount.text.toString().trim()
                if (text.isNotEmpty()) {
                    val value = text.toDoubleOrNull()
                    if (value != null) {
                        etAmount.setText(String.format("%.2f", value))
                    }
                }
            }
        }

        // —— 自定义Dialog（替代AlertDialog，支持动画和磨砂背景） ——
        val dialog = Dialog(requireContext(), R.style.GlassDialog)
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            // 底部弹窗样式
            attributes?.windowAnimations = android.R.style.Animation_Dialog
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 弹窗左右边距 32dp
            val margin = (32 * resources.displayMetrics.density + 0.5f).toInt()
            val lp = attributes
            lp?.width = resources.displayMetrics.widthPixels - margin * 2
            lp?.horizontalMargin = 0f
            attributes = lp
            // windowSoftInputMode: 键盘弹出时弹窗上移
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // —— 按钮事件 ——
        btnCancel.setOnClickListener {
            animateButtonPress(btnCancel)
            dialog.dismiss()
        }
        btnSave.setOnClickListener {
            animateButtonPress(btnSave)
            val amtStr = etAmount.text.toString().trim()
            if (amtStr.isEmpty()) {
                Toast.makeText(requireContext(), "请输入金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val amt = amtStr.toDoubleOrNull()
            if (amt == null || amt <= 0) {
                Toast.makeText(requireContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val date = tvDate.text.toString().trim()
            val remark = etRemark.text.toString().trim()
            saveAccountRecord(currentType, amt, selectedCategory, remark)
            Toast.makeText(requireContext(), "记账成功", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
        updateSegmentUI()
    }

    // ========== 编辑账单弹窗 ==========

    /** 点击最近账单条目 → 弹出编辑账单弹窗，预填已有数据 */
    private fun showEditBillDialog(account: AccountBean) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_account_form, null)

        // —— 视图引用 ——
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val etAmount = dialogView.findViewById<EditText>(R.id.et_amount)
        val tvSegExpense = dialogView.findViewById<TextView>(R.id.tv_segment_expense)
        val tvSegIncome = dialogView.findViewById<TextView>(R.id.tv_segment_income)
        val llCategoryTags = dialogView.findViewById<LinearLayout>(R.id.ll_category_tags)
        val tvDate = dialogView.findViewById<TextView>(R.id.tv_date)
        val llDateRow = dialogView.findViewById<LinearLayout>(R.id.ll_date_row)
        val etRemark = dialogView.findViewById<EditText>(R.id.et_remark)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnSave = dialogView.findViewById<TextView>(R.id.btn_save)

        tvTitle.text = "编辑账单"

        // —— 分类定义 ——
        val expenseCategories = arrayOf("餐饮", "交通", "购物", "娱乐", "医疗", "教育", "通信", "居家", "转账", "其他支出")
        val incomeCategories = arrayOf("工资", "红包", "投资", "其他收入")
        val categoryIcons = mapOf(
            "餐饮" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("餐饮"),
            "交通" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("交通"),
            "购物" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("购物"),
            "娱乐" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("娱乐"),
            "医疗" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("医疗"),
            "教育" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("教育"),
            "通信" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("通信"),
            "居家" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("居家"),
            "转账" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("转账"),
            "其他支出" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("其他支出"),
            "工资" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("工资"),
            "红包" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("红包"),
            "投资" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("投资"),
            "其他收入" to com.voice.accountbook.util.CategoryIconHelper.getIconResId("其他收入")
        )

        // —— 状态 ——
        var currentType = account.type  // 从已有记录读取
        var selectedCategory = account.category  // 从已有记录读取

        // —— 更新分类标签栏 ——
        fun updateCategoryTags() {
            llCategoryTags.removeAllViews()
            val cats = if (currentType == 0) expenseCategories else incomeCategories

            if (selectedCategory.isEmpty() || !cats.contains(selectedCategory)) {
                selectedCategory = cats[0]
            }

            for (cat in cats) {
                val tag = createCategoryTag(cat, cat == selectedCategory, categoryIcons[cat])
                tag.setOnClickListener {
                    selectedCategory = cat
                    updateCategoryTags()
                }
                llCategoryTags.addView(tag)
            }
        }

        // —— 更新类型分段控件样式 ——
        fun updateSegmentUI() {
            val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
            val r = dp(18f).toFloat()

            if (currentType == 0) {
                val expenseBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#F87171"))
                }
                tvSegExpense.background = expenseBg
                tvSegExpense.setTextColor(Color.WHITE)
                tvSegIncome.background = null
                tvSegIncome.setTextColor(Color.parseColor("#C8BDE6"))
            } else {
                val incomeBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#34D399"))
                }
                tvSegIncome.background = incomeBg
                tvSegIncome.setTextColor(Color.WHITE)
                tvSegExpense.background = null
                tvSegExpense.setTextColor(Color.parseColor("#C8BDE6"))
            }
        }

        // —— 预填数据 ——
        etAmount.setText(if (account.money == account.money.toLong().toDouble())
            account.money.toLong().toString() else account.money.toString())
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(account.time)
        tvDate.text = dateStr
        etRemark.setText(account.remark ?: "")

        updateCategoryTags()

        // —— 类型切换事件 ——
        tvSegExpense.setOnClickListener {
            if (currentType != 0) { currentType = 0; updateSegmentUI(); updateCategoryTags() }
        }
        tvSegIncome.setOnClickListener {
            if (currentType != 1) { currentType = 1; updateSegmentUI(); updateCategoryTags() }
        }

        // —— 日期选择 ——
        llDateRow.setOnClickListener {
            val cal = Calendar.getInstance()
            cal.timeInMillis = account.time
            showGlassDatePicker(cal) { y, m, d ->
                tvDate.text = String.format("%04d-%02d-%02d", y, m + 1, d)
            }
        }

        // —— 金额格式化 ——
        etAmount.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = etAmount.text.toString().trim()
                if (text.isNotEmpty()) {
                    val value = text.toDoubleOrNull()
                    if (value != null) {
                        etAmount.setText(String.format("%.2f", value))
                    }
                }
            }
        }

        // —— Dialog ——
        val dialog = Dialog(requireContext(), R.style.GlassDialog)
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes?.windowAnimations = android.R.style.Animation_Dialog
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val margin = (32 * resources.displayMetrics.density + 0.5f).toInt()
            val lp = attributes
            lp?.width = resources.displayMetrics.widthPixels - margin * 2
            lp?.horizontalMargin = 0f
            attributes = lp
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // —— 按钮事件 ——
        btnCancel.setOnClickListener {
            animateButtonPress(btnCancel)
            dialog.dismiss()
        }
        btnSave.setOnClickListener {
            animateButtonPress(btnSave)
            val amtStr = etAmount.text.toString().trim()
            if (amtStr.isEmpty()) {
                Toast.makeText(requireContext(), "请输入金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val amt = amtStr.toDoubleOrNull()
            if (amt == null || amt <= 0) {
                Toast.makeText(requireContext(), "请输入有效的金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val date = tvDate.text.toString().trim()
            val remark = etRemark.text.toString().trim()

            // 解析日期为时间戳
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val parsedTime = try {
                sdf.parse(date)?.time ?: account.time
            } catch (e: Exception) {
                account.time
            }

            val updatedAccount = account.copy(
                money = amt,
                type = currentType,
                category = selectedCategory,
                time = parsedTime,
                remark = remark.ifEmpty { null }
            )

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    accountDao.update(updatedAccount)
                    withContext(Dispatchers.Main) {
                        homeViewModel.refreshAll()
                        Toast.makeText(requireContext(), "账单已更新", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("HomeFragment", "更新账单失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "更新账单失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.dismiss()
        }

        dialog.show()
        updateSegmentUI()
    }

    // ========== 玻璃日期选择器（替代系统DatePickerDialog） ==========

    /**
     * 自定义玻璃风格日期选择器
     * - 底部弹出，bg_dialog背景，28dp圆角
     * - 标题显示选中日期 "yyyy年M月d日"，18sp Bold 白色
     * - 月份左右箭头导航
     * - 6×7 日历网格，本月日期可点击，选中态 #9F7AFF 圆形背景
     * - 底部取消/确定按钮
     */
    private fun showGlassDatePicker(currentDate: Calendar, onDateSelected: (Int, Int, Int) -> Unit) {
        val dialog = Dialog(requireContext(), R.style.GlassDialog)
        val dp = resources.displayMetrics.density
        val dpInt = { v: Float -> (v * dp + 0.5f).toInt() }

        // 选中状态（可变）
        var selYear = currentDate.get(Calendar.YEAR)
        var selMonth = currentDate.get(Calendar.MONTH)  // 0-based
        var selDay = currentDate.get(Calendar.DAY_OF_MONTH)

        // 当前展示的年月
        var displayYear = selYear
        var displayMonth = selMonth

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.bg_dialog, null)
            setPadding(dpInt(24f), dpInt(24f), dpInt(24f), dpInt(24f))
        }

        // 标题行：左箭头 | 日期文字 | 右箭头
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(20f) }
        }

        val tvTitleDate = TextView(requireContext()).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        // 日历网格容器（6行×7列）— 必须在 updateCalendarDisplay 和 btnPrevMonth 之前创建
        val gridContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(24f) }
        }
        val gridRows = Array(6) { rowIdx ->
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpInt(40f)
                )
            }
        }
        gridRows.forEach { gridContainer.addView(it) }
        root.addView(gridContainer)

        // 更新日历显示函数
        fun updateCalendarDisplay() {
            tvTitleDate.text = "${displayYear}年${displayMonth + 1}月${selDay}日"

            val cal = Calendar.getInstance()
            cal.set(displayYear, displayMonth, 1)
            val firstDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            cal.set(displayYear, displayMonth, 1)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            val prevMonthDays = cal.get(Calendar.DAY_OF_MONTH)

            var dayCounter = 1
            var nextMonthCounter = 1

            for (row in 0..5) {
                val rowLayout = gridRows[row]
                rowLayout.removeAllViews()
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val tv = TextView(requireContext()).apply {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, dpInt(40f), 1f)
                    }

                    if (cellIndex < firstDayOfWeek) {
                        val prevDay = prevMonthDays - firstDayOfWeek + cellIndex + 1
                        tv.text = prevDay.toString()
                        tv.textSize = 14f
                        tv.setTextColor(Color.parseColor("#44C8BDE6"))
                        tv.isClickable = false
                    } else if (dayCounter <= daysInMonth) {
                        val day = dayCounter
                        tv.text = day.toString()
                        tv.textSize = 14f
                        tv.isClickable = true
                        val isSelected = displayYear == selYear && displayMonth == selMonth && day == selDay
                        if (isSelected) {
                            tv.setTextColor(Color.WHITE)
                            tv.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setSize(dpInt(32f), dpInt(32f))
                                setColor(Color.parseColor("#9F7AFF"))
                            }
                        } else {
                            tv.setTextColor(Color.parseColor("#C8BDE6"))
                            tv.background = null
                        }
                        tv.setOnClickListener {
                            selYear = displayYear
                            selMonth = displayMonth
                            selDay = day
                            updateCalendarDisplay()
                        }
                        dayCounter++
                    } else {
                        tv.text = nextMonthCounter.toString()
                        tv.textSize = 14f
                        tv.setTextColor(Color.parseColor("#44C8BDE6"))
                        tv.isClickable = false
                        nextMonthCounter++
                    }
                    rowLayout.addView(tv)
                }
            }
        }

        val btnPrevMonth = TextView(requireContext()).apply {
            text = "<"
            textSize = 20f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpInt(44f), dpInt(44f))
            setOnClickListener {
                displayMonth--
                if (displayMonth < 0) { displayMonth = 11; displayYear-- }
                updateCalendarDisplay()
            }
        }

        val btnNextMonth = TextView(requireContext()).apply {
            text = ">"
            textSize = 20f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpInt(44f), dpInt(44f))
            setOnClickListener {
                displayMonth++
                if (displayMonth > 11) { displayMonth = 0; displayYear++ }
                updateCalendarDisplay()
            }
        }

        titleRow.addView(btnPrevMonth)
        titleRow.addView(tvTitleDate)
        titleRow.addView(btnNextMonth)
        root.addView(titleRow)

        // 星期标题行
        val weekRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpInt(10f) }
        }
        val weekLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (lbl in weekLabels) {
            val wv = TextView(requireContext()).apply {
                text = lbl
                textSize = 13f
                setTextColor(Color.parseColor("#8E82B5"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpInt(32f), 1f)
            }
            weekRow.addView(wv)
        }
        root.addView(weekRow)

        // 按钮行
        val buttonRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpInt(48f)
            )
        }

        val btnCancel = TextView(requireContext()).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(20f).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 2f
            ).apply { marginEnd = dpInt(12f) }
            setOnClickListener { dialog.dismiss() }
        }

        val btnOk = TextView(requireContext()).apply {
            text = "确定"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpInt(20f).toFloat()
                setColor(Color.parseColor("#9F7AFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 3f
            )
            setOnClickListener {
                onDateSelected(selYear, selMonth, selDay)
                dialog.dismiss()
            }
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnOk)
        root.addView(buttonRow)

        updateCalendarDisplay()

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.BOTTOM)
            attributes?.windowAnimations = android.R.style.Animation_Dialog
        }
        dialog.show()
    }

    /**
     * 创建分类标签View（横向滚动栏中的单个标签）
     * 选中态：图标+文字变为主紫色，背景微透主色填充
     */
    private fun createCategoryTag(name: String, isSelected: Boolean, iconRes: Int?): View {
        val dp = (requireContext().resources.displayMetrics.density)
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
        }

        // 图标
        val icon = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                (20 * dp).toInt(), (20 * dp).toInt()
            ).apply { marginEnd = (6 * dp).toInt() }
            if (iconRes != null) setImageResource(iconRes)
            if (isSelected) setColorFilter(Color.parseColor("#9F7AFF"))
            else setColorFilter(Color.parseColor("#C8BDE6"))
        }
        container.addView(icon)

        // 分类名
        val label = TextView(requireContext()).apply {
            text = name
            textSize = 12f
            setTextColor(if (isSelected) Color.parseColor("#9F7AFF") else Color.parseColor("#C8BDE6"))
        }
        container.addView(label)

        // 选中态背景
        if (isSelected) {
            container.background = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: Canvas) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)
                    p.color = Color.parseColor("#209F7AFF")
                    p.style = Paint.Style.FILL
                    canvas.drawRoundRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(),
                        (12 * dp), (12 * dp), p)
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
        } else {
            container.background = null
        }

        return container
    }

    // ========== 动画 ==========

    private fun startRecordingAnimation() {
        val anim = ScaleAnimation(1.0f, 1.1f, 1.0f, 1.1f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        anim.duration = 1000; anim.repeatMode = Animation.REVERSE; anim.repeatCount = Animation.INFINITE
        binding.fabRecord.startAnimation(anim)
    }

    private fun stopRecordingAnimation() {
        binding.fabRecord.clearAnimation()
    }

    private fun animateButtonPress(view: View) {
        val anim = ScaleAnimation(1.0f, 0.95f, 1.0f, 0.95f,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        anim.duration = 150; anim.repeatCount = 0; anim.fillAfter = true
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationEnd(a: Animation?) {
                val bounce = ScaleAnimation(0.95f, 1.0f, 0.95f, 1.0f,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
                bounce.duration = 150
                view.startAnimation(bounce)
            }
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationStart(a: Animation?) {}
        })
        view.startAnimation(anim)
    }

    /** 交错卡片入场动画 */
    private fun animateCardEntrance() {
        val cards = listOf(binding.cvStatistics, binding.cvRecentBills, binding.llBudgetTip,
            binding.llButtons, binding.fabRecord, binding.fabManual)
        cards.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 20f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setStartDelay((index * 50).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /** 金额数字变化动画（ValueAnimator平滑过渡） */
    private fun animateNumberChange(textView: TextView, prefix: String, from: Double, to: Double, isColorAnim: Boolean) {
        val anim = ValueAnimator.ofFloat(from.toFloat(), to.toFloat())
        anim.duration = 200
        anim.addUpdateListener { 
            textView.text = "$prefix${String.format("%.2f", it.animatedValue)}"
        }
        anim.start()
    }

    private fun showLightToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        val vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (vibrator.hasVibrator()) vibrator.vibrate(50)
    }
}