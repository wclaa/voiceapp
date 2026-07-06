package com.voice.accountbook.fragment

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.voice.accountbook.R
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.databinding.FragmentBillBinding
import com.voice.accountbook.entity.AccountBean
import com.voice.accountbook.viewmodel.BillViewModel
import com.voice.accountbook.adapter.BillDayGroupAdapter
import com.voice.accountbook.util.CategoryIconHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ViewModel工厂类 - 新增：用于创建带参数的BillViewModel
 */
class BillViewModelFactory(
    private val accountDatabase: AccountDatabase
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BillViewModel::class.java)) {
            return BillViewModel(accountDatabase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * 账单页面Fragment
 * 显示按天分组的记账记录
 */
class BillFragment : Fragment() {

    companion object {
        var filterCategory: String? = null
        var filterType: Int? = null // 0=expense, 1=income
    }

    // ViewBinding
    private lateinit var binding: FragmentBillBinding

    // ViewModel
    private lateinit var billViewModel: BillViewModel

    // 适配器
    private lateinit var billAdapter: BillDayGroupAdapter

    // 分类定义
    private val expenseCategories = arrayOf("餐饮", "交通", "购物", "娱乐", "医疗", "教育", "通信", "居家", "转账", "其他支出")
    private val incomeCategories = arrayOf("工资", "红包", "投资", "其他收入")

    // 分类图标映射
    private val categoryIcons = mapOf(
        "餐饮" to CategoryIconHelper.getIconResId("餐饮"),
        "交通" to CategoryIconHelper.getIconResId("交通"),
        "购物" to CategoryIconHelper.getIconResId("购物"),
        "娱乐" to CategoryIconHelper.getIconResId("娱乐"),
        "医疗" to CategoryIconHelper.getIconResId("医疗"),
        "教育" to CategoryIconHelper.getIconResId("教育"),
        "通信" to CategoryIconHelper.getIconResId("通信"),
        "居家" to CategoryIconHelper.getIconResId("居家"),
        "转账" to CategoryIconHelper.getIconResId("转账"),
        "其他支出" to CategoryIconHelper.getIconResId("其他支出"),
        "工资" to CategoryIconHelper.getIconResId("工资"),
        "红包" to CategoryIconHelper.getIconResId("红包"),
        "投资" to CategoryIconHelper.getIconResId("投资"),
        "其他收入" to CategoryIconHelper.getIconResId("其他收入")
    )

    // 过滤条件
    private var filterCategory: String? = null
    private var filterType: Int? = null
    private var searchQuery: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentBillBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化ViewModel - 修复：使用正确的ViewModelProvider.Factory
        val accountDatabase = AccountDatabase.getInstance(requireContext())
        val factory = BillViewModelFactory(accountDatabase)
        billViewModel = ViewModelProvider(this, factory)[BillViewModel::class.java]

        // 初始化RecyclerView
        initRecyclerView()

        // 观察数据变化 - 修复：使用collectWithLifecycle替代observe
        observeData()

        // 搜索和过滤按钮
        initSearchFilterButtons()
    }

    override fun onResume() {
        super.onResume()
        // 页面恢复时重新加载数据，确保统计数据最新
        // 检查伴随对象中的统计页传入的过滤条件
        val compCategory = BillFragment.filterCategory
        val compType = BillFragment.filterType
        if (compCategory != null || compType != null) {
            billViewModel.loadBillData(compCategory, compType)
            // 同时应用到实例过滤字段
            filterCategory = compCategory
            filterType = compType
            BillFragment.filterCategory = null
            BillFragment.filterType = null
        } else {
            billViewModel.loadBillData()
        }
    }

    /**
     * 初始化RecyclerView
     */
    private fun initRecyclerView() {
        billAdapter = BillDayGroupAdapter()
        binding.rvBillList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = billAdapter
        }

        // 条目点击 → 编辑弹窗
        billAdapter.onItemClick = { account -> showEditBillDialog(account) }
        // 条目长按 → 编辑/删除菜单
        billAdapter.onItemLongClick = { account ->
            val options = arrayOf("编辑", "删除")
            AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> showEditBillDialog(account)
                        1 -> {
                            AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                                .setTitle("确认删除")
                                .setMessage("确定要删除这条账单记录吗？")
                                .setPositiveButton("删除") { _, _ ->
                                    billViewModel.deleteAccount(account)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
                .show()
        }

        // 滑动删除
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (viewHolder is BillDayGroupAdapter.RecordViewHolder) {
                    val account = viewHolder.getAccount() ?: return
                    AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                        .setTitle("确认删除")
                        .setMessage("确定要删除这条账单记录吗？")
                        .setPositiveButton("删除") { _, _ ->
                            billViewModel.deleteAccount(account)
                        }
                        .setNegativeButton("取消") { _, _ ->
                            billAdapter.notifyItemChanged(viewHolder.adapterPosition)
                        }
                        .show()
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.rvBillList)
    }

    /**
     * 初始化搜索和过滤按钮
     */
    private fun initSearchFilterButtons() {
        binding.ivBillSearch.setOnClickListener {
            showSearchDialog()
        }
        binding.ivBillFilter.setOnClickListener {
            showFilterDialog()
        }
    }

    /**
     * 搜索弹窗（实时过滤）
     */
    private fun showSearchDialog() {
        val dp = resources.displayMetrics.density

        val dialog = Dialog(requireContext(), R.style.GlassDialog)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            val pad = (24 * dp + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val input = EditText(requireContext()).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8E82B5"))
            hint = "输入备注关键词搜索"
            background = null
            setPadding(0, (8 * dp + 0.5f).toInt(), 0, (8 * dp + 0.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            searchQuery?.let { setText(it); setSelection(it.length) }
        }

        val bottomLine = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * dp + 0.5f).toInt()
            )
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }

        input.setOnFocusChangeListener { _, hasFocus ->
            bottomLine.setBackgroundColor(
                if (hasFocus) Color.parseColor("#9F7AFF") else Color.parseColor("#33FFFFFF")
            )
        }

        container.addView(input)
        container.addView(bottomLine)

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = (32 * dp + 0.5f).toInt()
            val lp = attributes
            lp?.width = resources.displayMetrics.widthPixels - margin * 2
            attributes = lp
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()

        // 实时搜索监听
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.takeIf { it.isNotEmpty() }
                loadFilteredData()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    /**
     * 触发数据重新加载（应用当前过滤条件）
     */
    private fun loadFilteredData() {
        billViewModel.loadBillData(filterCategory, filterType)
    }

    /**
     * 分类过滤弹窗
     */
    private fun showFilterDialog() {
        val dp = resources.displayMetrics.density

        val scrollView = ScrollView(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_dialog)
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 标题
        val tvTitle = TextView(requireContext()).apply {
            text = "按分类过滤"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, (20 * dp + 0.5f).toInt(), 0, (12 * dp + 0.5f).toInt())
        }
        container.addView(tvTitle)

        // 先创建 dialog，以便后续 setOnClickListener 中可以引用
        val dialog = Dialog(requireContext(), R.style.GlassDialog)

        // 构建分组分类列表
        fun addSectionHeader(text: String) {
            val header = TextView(requireContext()).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#8E82B5"))
                setPadding(0, (12 * dp + 0.5f).toInt(), 0, (4 * dp + 0.5f).toInt())
            }
            container.addView(header)
        }

        fun addCategoryRow(cat: String) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * dp + 0.5f).toInt(), 0, (8 * dp + 0.5f).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    filterCategory = if (filterCategory == cat) null else cat
                    dialog.dismiss()
                    observeData()
                }
            }

            // 图标
            val icon = ImageView(requireContext()).apply {
                val iconRes = categoryIcons[cat]
                if (iconRes != null) setImageResource(iconRes)
                setColorFilter(Color.parseColor("#C8BDE6"))
                layoutParams = LinearLayout.LayoutParams(
                    (24 * dp + 0.5f).toInt(),
                    (24 * dp + 0.5f).toInt()
                ).apply { marginEnd = (12 * dp + 0.5f).toInt() }
            }
            row.addView(icon)

            // 分类名
            val name = TextView(requireContext()).apply {
                text = cat
                textSize = 15f
                setTextColor(Color.parseColor("#C8BDE6"))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            row.addView(name)

            // 单选指示器
            row.addView(createRadioIndicator(cat == filterCategory))
            container.addView(row)
        }

        addSectionHeader("支出分类")
        expenseCategories.forEach { addCategoryRow(it) }
        addSectionHeader("收入分类")
        incomeCategories.forEach { addCategoryRow(it) }

        // 取消按钮
        val btnCancel = TextView(requireContext()).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp + 0.5f).toInt(), 0, (12 * dp + 0.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnCancel)

        scrollView.addView(container)

        dialog.setContentView(scrollView)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_Dialog)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = (32 * dp + 0.5f).toInt()
            val lp = attributes
            lp?.width = resources.displayMetrics.widthPixels - margin * 2
            attributes = lp
        }
        dialog.show()
    }

    /**
     * 设置分类过滤（外部调用）
     */
    fun setCategoryFilter(category: String?) {
        filterCategory = category
        observeData()
    }

    /**
     * 观察数据变化 - 修复：使用StateFlow的collect方法
     */
    private fun observeData() {
        // 使用lifecycleScope.launch和repeatOnLifecycle来收集StateFlow
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 观察本月总支出
                launch {
                    billViewModel.monthlyExpense.collect { expense ->
                        binding.tvBillTotalExpense.text = "总支出 ¥" + String.format("%.2f", expense)
                    }
                }

                // 观察按天分组的账单数据
                launch {
                    billViewModel.billDayGroups.collect { groups ->
                        // 应用过滤条件
                        var filtered = groups
                        filterCategory?.let { cat ->
                            filtered = filtered.map { group ->
                                group.copy(accounts = group.accounts.filter { it.category == cat })
                            }.filter { it.accounts.isNotEmpty() }
                        }
                        filterType?.let { type ->
                            filtered = filtered.map { group ->
                                group.copy(accounts = group.accounts.filter { it.type == type })
                            }.filter { it.accounts.isNotEmpty() }
                        }
                        searchQuery?.let { q ->
                            filtered = filtered.map { group ->
                                group.copy(accounts = group.accounts.filter {
                                    (it.remark ?: "").contains(q, ignoreCase = true)
                                })
                            }.filter { it.accounts.isNotEmpty() }
                        }
                        billAdapter.submitList(filtered)
                        if (filtered.isEmpty()) {
                            binding.tvEmptyBillHint.visibility = View.VISIBLE
                            binding.rvBillList.visibility = View.GONE
                        } else {
                            binding.tvEmptyBillHint.visibility = View.GONE
                            binding.rvBillList.visibility = View.VISIBLE
                        }
                    }
                }

                // 更新月份标签
                launch {
                    val month = Calendar.getInstance().get(Calendar.MONTH) + 1
                    binding.tvBillMonthLabel.text = "${month}月账单"
                }
            }
        }
    }

    // ========== 编辑账单弹窗 ==========

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

        // —— 统一按钮样式 ——
        val dp = { v: Float -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        btnCancel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(Color.parseColor("#662A1C46"))
        }
        btnCancel.setTextColor(Color.parseColor("#C8BDE6"))
        btnSave.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(Color.parseColor("#9F7AFF"))
        }
        btnSave.setTextColor(Color.WHITE)

        // —— 状态 ——
        var currentType = account.type
        var selectedCategory = account.category

        // —— 更新分类标签栏 ——
        fun updateCategoryTags() {
            llCategoryTags.removeAllViews()
            val cats = if (currentType == 0) expenseCategories else incomeCategories

            // 确保selectedCategory在列表中
            if (!cats.contains(selectedCategory)) {
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
                val expenseBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#F87171"))
                }
                tvSegExpense.background = expenseBg
                tvSegExpense.setTextColor(Color.WHITE)
                tvSegIncome.background = null
                tvSegIncome.setTextColor(Color.parseColor("#C8BDE6"))
            } else {
                val incomeBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = r
                    setColor(Color.parseColor("#34D399"))
                }
                tvSegIncome.background = incomeBg
                tvSegIncome.setTextColor(Color.WHITE)
                tvSegExpense.background = null
                tvSegExpense.setTextColor(Color.parseColor("#C8BDE6"))
            }
        }

        // —— 类型切换事件 ——
        tvSegExpense.setOnClickListener {
            if (currentType != 0) { currentType = 0; updateSegmentUI(); updateCategoryTags() }
        }
        tvSegIncome.setOnClickListener {
            if (currentType != 1) { currentType = 1; updateSegmentUI(); updateCategoryTags() }
        }

        // —— 预填数据 ——
        val amtText = if (account.money == account.money.toLong().toDouble())
            account.money.toLong().toString() else String.format("%.2f", account.money)
        etAmount.setText(amtText)
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(account.time))
        tvDate.text = dateStr
        etRemark.setText(account.remark ?: "")
        updateCategoryTags()

        // 日期行点击 → 自定义玻璃日期选择器
        llDateRow.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = account.time }
            showGlassDatePicker(cal) { y, m, d ->
                tvDate.text = String.format("%04d-%02d-%02d", y, m, d)
            }
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

        // —— 自定义Dialog ——
        val dialog = Dialog(requireContext(), R.style.GlassDialog)
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            attributes?.windowAnimations = android.R.style.Animation_Dialog
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val margin = (32 * resources.displayMetrics.density + 0.5f).toInt()
            val lp = attributes
            lp?.width = resources.displayMetrics.widthPixels - margin * 2
            lp?.horizontalMargin = 0f
            attributes = lp
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // —— 按钮事件 ——
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
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
            val newDate = tvDate.text.toString().trim()
            val remark = etRemark.text.toString().trim()
            val newTime = try {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(newDate)?.time ?: account.time
            } catch (e: Exception) {
                account.time
            }

            val updatedAccount = account.copy(
                money = amt,
                type = currentType,
                category = selectedCategory,
                time = newTime,
                remark = remark.ifEmpty { null }
            )
            billViewModel.updateAccount(updatedAccount)
            Toast.makeText(requireContext(), "修改成功", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
        updateSegmentUI()
    }

    /**
     * 创建分类标签View（横向滚动栏中的单个标签）
     * 选中态：图标+文字变为主紫色，背景微透主色填充
     */
    private fun createCategoryTag(name: String, isSelected: Boolean, iconRes: Int?): View {
        val dp = requireContext().resources.displayMetrics.density
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

        return container
    }

    /**
     * 创建单选指示器View（16dp圆，选中=#9F7AFF填充+白点，未选中=#33FFFFFF描边）
     */
    private fun createRadioIndicator(isSelected: Boolean): View {
        val dp = resources.displayMetrics.density
        val size = (16 * dp + 0.5f).toInt()
        val dotSize = (8 * dp + 0.5f).toInt()
        val strokeW = (1 * dp + 0.5f).toInt()

        val view = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (12 * dp + 0.5f).toInt()
            }
            if (isSelected) {
                val outer = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#9F7AFF"))
                    setStroke(strokeW, Color.parseColor("#33FFFFFF"))
                }
                val inner = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                val layers = arrayOf(outer, inner)
                val layer = android.graphics.drawable.LayerDrawable(layers)
                val half = size / 2
                val halfDot = dotSize / 2
                layer.setLayerInset(0, 0, 0, 0, 0)
                layer.setLayerInset(1, half - halfDot, half - halfDot, half - halfDot, half - halfDot)
                background = layer
            } else {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(strokeW, Color.parseColor("#33FFFFFF"))
                }
                background = bg
            }
        }
        return view
    }

    /**
     * 自定义玻璃风格日期选择器
     */
    private fun showGlassDatePicker(currentDate: Calendar, onDateSelected: (Int, Int, Int) -> Unit) {
        val dp = resources.displayMetrics.density

        // 提前创建 dialog，供后续 onClick 引用
        val dialog = Dialog(requireContext(), R.style.GlassDialog)

        // 日期状态
        var selectedYear = currentDate.get(Calendar.YEAR)
        var selectedMonth = currentDate.get(Calendar.MONTH)
        var selectedDay = currentDate.get(Calendar.DAY_OF_MONTH)

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            val pad = (24 * dp + 0.5f).toInt()
            setPadding(pad, pad, pad, pad)
        }

        // 标题：yyyy年M月d日
        val tvTitle = TextView(requireContext()).apply {
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * dp + 0.5f).toInt())
        }
        fun updateTitle() {
            tvTitle.text = "${selectedYear}年${selectedMonth + 1}月${selectedDay}日"
        }
        updateTitle()
        container.addView(tvTitle)

        // 月份导航行："<" | yyyy年M月 | ">"
        val navRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * dp + 0.5f).toInt())
        }

        val btnPrev = TextView(requireContext()).apply {
            text = "‹"
            textSize = 24f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        val tvMonthLabel = TextView(requireContext()).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNext = TextView(requireContext()).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        fun updateMonthLabel() {
            tvMonthLabel.text = "${selectedYear}年${selectedMonth + 1}月"
        }
        updateMonthLabel()
        navRow.addView(btnPrev)
        navRow.addView(tvMonthLabel)
        navRow.addView(btnNext)
        container.addView(navRow)

        // 星期头部：一~日
        val weekHeader = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (4 * dp + 0.5f).toInt())
        }
        val weekDays = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (d in weekDays) {
            val label = TextView(requireContext()).apply {
                text = d
                textSize = 13f
                setTextColor(Color.parseColor("#8E82B5"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, (6 * dp + 0.5f).toInt(), 0, (6 * dp + 0.5f).toInt())
            }
            weekHeader.addView(label)
        }
        container.addView(weekHeader)

        // 日期网格：6行×7列
        val dateGrid = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun buildGrid() {
            dateGrid.removeAllViews()
            val cal = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }
            val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // Sunday=1
            val startOffset = ((firstDayOfWeek + 5) % 7) // Monday=0 ... Sunday=6
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            // 上月天数（填充前面空白）
            val prevCal = Calendar.getInstance().apply {
                set(selectedYear, selectedMonth, 1)
                add(Calendar.DAY_OF_MONTH, -1)
            }
            val prevDays = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)

            var dayCounter = 1

            for (row in 0 until 6) {
                val rowLayout = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    val cell = TextView(requireContext()).apply {
                        gravity = Gravity.CENTER
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(0, (8 * dp + 0.5f).toInt(), 0, (8 * dp + 0.5f).toInt())
                    }

                    when {
                        cellIndex < startOffset -> {
                            // 上月日期
                            val prevDay = prevDays - startOffset + cellIndex + 1
                            cell.text = prevDay.toString()
                            cell.setTextColor(Color.parseColor("#44C8BDE6"))
                            cell.isClickable = false
                        }
                        dayCounter > daysInMonth -> {
                            // 下月日期
                            cell.text = (dayCounter - daysInMonth).toString()
                            cell.setTextColor(Color.parseColor("#44C8BDE6"))
                            cell.isClickable = false
                            dayCounter++
                        }
                        else -> {
                            cell.text = dayCounter.toString()
                            val isSel = (dayCounter == selectedDay)
                            if (isSel) {
                                cell.setTextColor(Color.WHITE)
                                val selBg = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.parseColor("#9F7AFF"))
                                }
                                cell.background = selBg
                            } else {
                                cell.setTextColor(Color.parseColor("#C8BDE6"))
                                cell.background = null
                            }
                            cell.isClickable = true
                            cell.setOnClickListener {
                                selectedDay = dayCounter
                                updateTitle()
                                buildGrid()
                            }
                            dayCounter++
                        }
                    }
                    rowLayout.addView(cell)
                }
                dateGrid.addView(rowLayout)
            }
        }

        buildGrid()
        container.addView(dateGrid)

        // 底部按钮区
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * dp + 0.5f).toInt(), 0, 0)
        }

        val btnCancel = TextView(requireContext()).apply {
            text = "取消"
            textSize = 15f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp + 0.5f).toInt(), 0, (12 * dp + 0.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (14 * dp)
                setColor(Color.parseColor("#662A1C46"))
            }
            setOnClickListener { dialog.dismiss() }
        }
        val btnOk = TextView(requireContext()).apply {
            text = "确定"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp + 0.5f).toInt(), 0, (12 * dp + 0.5f).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (14 * dp)
                setColor(Color.parseColor("#9F7AFF"))
            }
            setOnClickListener {
                onDateSelected(selectedYear, selectedMonth, selectedDay)
                dialog.dismiss()
            }
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnOk)
        container.addView(btnRow)

        // 月份导航事件
        btnPrev.setOnClickListener {
            if (selectedMonth == 0) { selectedMonth = 11; selectedYear-- }
            else { selectedMonth-- }
            val maxDay = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
            selectedDay = minOf(selectedDay, maxDay)
            updateTitle()
            updateMonthLabel()
            buildGrid()
        }
        btnNext.setOnClickListener {
            if (selectedMonth == 11) { selectedMonth = 0; selectedYear++ }
            else { selectedMonth++ }
            val maxDay = Calendar.getInstance().apply { set(selectedYear, selectedMonth, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
            selectedDay = minOf(selectedDay, maxDay)
            updateTitle()
            updateMonthLabel()
            buildGrid()
        }

        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_Dialog)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }
}