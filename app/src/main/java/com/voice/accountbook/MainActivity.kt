package com.voice.accountbook

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.voice.accountbook.databinding.ActivityMainBinding
import com.voice.accountbook.fragment.HomeFragment
import com.voice.accountbook.fragment.StatsFragment
import com.voice.accountbook.fragment.BillFragment
import com.voice.accountbook.fragment.ProfileFragment

/**
 * 主活动
 * 作为Fragment宿主页面，包含底部导航栏和ViewPager2（4个Tab）
 */
class MainActivity : AppCompatActivity() {

    // ViewBinding
    private lateinit var binding: ActivityMainBinding

    // ViewPager2适配器
    private lateinit var viewPagerAdapter: ViewPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化ViewPager2
        initViewPager()

        // 初始化底部导航栏
        initBottomNavigation()
    }

    /**
     * 初始化ViewPager2
     */
    private fun initViewPager() {
        viewPagerAdapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = viewPagerAdapter

        // 禁止预加载，避免Fragment初始化时影响原有首页的模型加载逻辑
        binding.viewPager.offscreenPageLimit = 1

        // 设置页面切换淡入淡出动画
        binding.viewPager.setPageTransformer { page, position ->
            page.alpha = 1 - kotlin.math.abs(position).coerceIn(0f, 1f) * 0.3f
            page.translationX = 0f
        }

        // ViewPager2页面切换监听
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // 同步底部导航栏选中状态
                when (position) {
                    0 -> binding.bottomNavigation.selectedItemId = R.id.nav_home
                    1 -> binding.bottomNavigation.selectedItemId = R.id.nav_stats
                    2 -> binding.bottomNavigation.selectedItemId = R.id.nav_bill
                    3 -> binding.bottomNavigation.selectedItemId = R.id.nav_profile
                }
            }
        })
    }

    /**
     * 初始化底部导航栏
     */
    private fun initBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> {
                    binding.viewPager.currentItem = 0
                    true
                }
                R.id.nav_stats -> {
                    binding.viewPager.currentItem = 1
                    true
                }
                R.id.nav_bill -> {
                    binding.viewPager.currentItem = 2
                    true
                }
                R.id.nav_profile -> {
                    binding.viewPager.currentItem = 3
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 切换到账单Tab
     */
    fun switchToBillTab() {
        binding.viewPager.currentItem = 2
    }

    /**
     * ViewPager2适配器（4个Tab）
     */
    inner class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> StatsFragment()
                2 -> BillFragment()
                3 -> ProfileFragment()
                else -> HomeFragment()
            }
        }
    }
}