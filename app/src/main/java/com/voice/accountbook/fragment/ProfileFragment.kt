package com.voice.accountbook.fragment

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voice.accountbook.R
import com.voice.accountbook.database.AccountDatabase
import com.voice.accountbook.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

/**
 * 个人设置页 — 月度预算设置
 */
class ProfileFragment : Fragment() {

    private lateinit var homeViewModel: HomeViewModel
    private val db by lazy { AccountDatabase.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 应用保存的夜间模式
        val savedMode = requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(savedMode)

        val scrollView = android.widget.ScrollView(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#1B1130"))
        }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        // 标题
        val title = TextView(requireContext()).apply {
            text = "我的"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(28))
        }
        root.addView(title)

        // ===== Header area with avatar =====
        val headerContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(24), dp(24), dp(24), dp(32))
            }
        }

        val avatarView = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            val avatarBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9F7AFF"))
            }
            background = avatarBg
            setImageResource(R.drawable.ic_profile)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val textArea = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16)
            }
        }
        val nameText = TextView(requireContext()).apply {
            text = "离线记账"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val subText = TextView(requireContext()).apply {
            text = "全本地数据存储"
            textSize = 12f
            setTextColor(Color.parseColor("#8E82B5"))
        }
        textArea.addView(nameText)
        textArea.addView(subText)
        headerContainer.addView(avatarView)
        headerContainer.addView(textArea)
        root.addView(headerContainer)

        // ===== 月度预算设置卡片 =====
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(com.voice.accountbook.R.drawable.bg_glass_card, null)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        card.layoutParams = cardParams

        val cardTitle = TextView(requireContext()).apply {
            text = "月度预算设置"
            textSize = 15f
            setTextColor(Color.parseColor("#C8BDE6"))
            setPadding(0, 0, 0, dp(12))
        }
        card.addView(cardTitle)

        val budgetInfo = TextView(requireContext()).apply {
            id = View.generateViewId()
            text = "加载中..."
            textSize = 13f
            setTextColor(Color.parseColor("#8E82B5"))
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(budgetInfo)

        val setBtn = TextView(requireContext()).apply {
            text = "设置本月预算"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#9F7AFF"))
            }
            background = bg
        }
        card.addView(setBtn)

        root.addView(card)

        // ===== Settings card =====
        val settingsCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(24), dp(16), dp(24), dp(24))
            }
            background = resources.getDrawable(R.drawable.bg_glass_card, null)
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }

        val settings = listOf(
            Pair("外观设置", android.R.drawable.ic_menu_gallery),
            Pair("分类管理", R.drawable.ic_cat_food),
            Pair("语音设置", R.drawable.ic_mic),
            Pair("数据导出", R.drawable.ic_cat_investment),
            Pair("清空所有数据", android.R.drawable.ic_menu_delete),
            Pair("关于", android.R.drawable.ic_menu_info_details)
        )

        for ((index, item) in settings.withIndex()) {
            val first = item.first; val iconRes = item.second
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
                )
                setOnClickListener {
                    when (first) {
                        "外观设置" -> {
                            val modes = arrayOf("跟随系统", "深色模式", "浅色模式")
                            AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                                .setTitle("外观设置")
                                .setItems(modes) { _, which ->
                                    val mode = when (which) {
                                        0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                        1 -> AppCompatDelegate.MODE_NIGHT_YES
                                        2 -> AppCompatDelegate.MODE_NIGHT_NO
                                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                    }
                                    AppCompatDelegate.setDefaultNightMode(mode)
                                    requireActivity().getSharedPreferences("settings", Context.MODE_PRIVATE)
                                        .edit().putInt("night_mode", mode).apply()
                                }
                                .show()
                        }
                        "分类管理" -> Toast.makeText(requireContext(), "分类管理（开发中）", Toast.LENGTH_SHORT).show()
                        "语音设置" -> Toast.makeText(requireContext(), "语音设置（开发中）", Toast.LENGTH_SHORT).show()
                        "数据导出" -> Toast.makeText(requireContext(), "数据导出（开发中）", Toast.LENGTH_SHORT).show()
                        "清空所有数据" -> showClearDataDialog()
                        "关于" -> {
                            val aboutMsg = "离线语音记账 v1.0\n\n" +
                                "全本地数据存储，无需网络连接\n" +
                                "所有账单数据仅保存在您的设备上\n\n" +
                                "开源组件：\n" +
                                "Vosk - 离线语音识别\n" +
                                "HanLP - 中文语义解析\n" +
                                "Room - 数据持久化"
                            AlertDialog.Builder(requireContext(), R.style.GlassDialog)
                                .setTitle("关于")
                                .setMessage(aboutMsg)
                                .setPositiveButton("确定", null)
                                .show()
                        }
                    }
                }
            }
            val icon = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    marginEnd = dp(16)
                }
                setImageResource(iconRes)
                setColorFilter(Color.parseColor("#C8BDE6"))
            }
            val text = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
                )
                text = first
                textSize = 14f
                setTextColor(Color.parseColor("#C8BDE6"))
                gravity = Gravity.CENTER_VERTICAL
            }
            val arrow = TextView(requireContext()).apply {
                setText("›")
                textSize = 20f
                setTextColor(Color.parseColor("#8E82B5"))
            }
            row.addView(icon); row.addView(text); row.addView(arrow)
            settingsCard.addView(row)
            if (index < settings.size - 1) {
                val divider = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply {
                        marginStart = dp(40)
                    }
                    setBackgroundColor(Color.parseColor("#0FFFFFFF"))
                }
                settingsCard.addView(divider)
            }
        }

        root.addView(settingsCard)

        // 版本信息
        val spacer = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val versionInfo = TextView(requireContext()).apply {
            text = "离线语音记账 v1.0"
            textSize = 12f
            setTextColor(Color.parseColor("#8E82B5"))
            setPadding(0, 0, 0, dp(32))
        }
        root.addView(versionInfo)

        scrollView.addView(root)

        // ===== ViewModel观察 =====
        homeViewModel = ViewModelProvider(requireActivity(),
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(db) as T
                }
            }
        )[HomeViewModel::class.java]

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    homeViewModel.monthlyBudget.collect { budget ->
                        if (budget != null) {
                            budgetInfo.text = "当前预算：¥${String.format("%.0f", budget)}（${homeViewModel.currentYearMonth.value}）"
                        } else {
                            budgetInfo.text = "尚未设置本月预算"
                        }
                    }
                }
            }
        }

        setBtn.setOnClickListener {
            showSetBudgetDialog(budgetInfo)
        }

        // 页面加载淡入动画
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(400).start()

        return scrollView
    }

    private fun showSetBudgetDialog(infoTv: TextView) {
        val dialog = Dialog(requireContext(), R.style.GlassDialog)

        // —— 整体容器 ——
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.bg_dialog, null)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // 标题
        val tvTitle = TextView(requireContext()).apply {
            text = "设置月度预算"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(tvTitle)

        // 金额输入行（¥前缀 + EditText）
        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        }

        val currencySymbol = TextView(requireContext()).apply {
            text = "¥"
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        inputRow.addView(currencySymbol)

        val input = EditText(requireContext()).apply {
            textSize = 32f
            setTextColor(Color.WHITE)
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            background = null
            hint = "0.00"
            setHintTextColor(Color.parseColor("#8E82B5"))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            val currentBudget = homeViewModel.monthlyBudget.value
            if (currentBudget != null && currentBudget > 0) setText(String.format("%.2f", currentBudget))
        }
        inputRow.addView(input)
        container.addView(inputRow)

        // 底部分隔线
        val bottomLine = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        container.addView(bottomLine)

        // 焦点变化时高亮底部分隔线
        input.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            bottomLine.setBackgroundColor(
                if (hasFocus) Color.parseColor("#9F7AFF") else Color.parseColor("#33FFFFFF")
            )
        }

        // 按钮行
        val btnRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(20)
            }
        }

        val btnCancel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f
            ).apply { marginEnd = dp(8) }
        }
        val tvCancel = TextView(requireContext()).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#C8BDE6"))
            gravity = Gravity.CENTER
        }
        btnCancel.addView(tvCancel)
        btnRow.addView(btnCancel)

        val btnOk = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#9F7AFF"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f
            ).apply { marginStart = dp(8) }
        }
        val tvOk = TextView(requireContext()).apply {
            text = "确定"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        btnOk.addView(tvOk)
        btnRow.addView(btnOk)

        container.addView(btnRow)

        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // 自动弹出键盘
        input.postDelayed({
            input.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        // 按钮事件
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnOk.setOnClickListener {
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
            } else {
                Toast.makeText(requireContext(), "请输入金额", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showClearDataDialog() {
        AlertDialog.Builder(requireContext(), R.style.GlassDialog)
            .setTitle("清空所有数据")
            .setMessage("此操作将删除所有账单记录和预算设置，且不可恢复。确定要继续吗？")
            .setPositiveButton("确认清空") { _, _ ->
                lifecycleScope.launch {
                    db.accountDao().deleteAll()
                    db.budgetDao().deleteAll()
                    Toast.makeText(requireContext(), "所有数据已清空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}