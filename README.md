# VoiceAccountBook — 离线语音智能记账系统

> 一款全离线运行的 Android 语音记账 App。说出消费内容，自动识别金额、分类、时间并完成记账。无需网络、无需注册，数据 100% 存储在你的设备上。

---

## 核心功能

### 语音记账
长按录音按钮说出消费内容（如「今天中午食堂吃饭花了二十块」），系统自动识别并提取金额、收支类型、分类、时间、备注，一键确认保存。实时显示识别文字流，支持解析后手动修改。

### 手动记账
自定义 GlassDialog 表单，录入金额/分类/日期/备注。支出 14 个分类，收入 4 个分类。

### 智能预算管理
- 每月独立设置预算，首页环形进度条实时显示消耗比例
- **今日还可消费** = (总预算 − 已支出) ÷ 剩余天数，比「剩余预算」更实用
- 切换月份自动加载对应月份预算

### 多维统计分析
- 月度收支总览（收入/支出/结余）
- **日收支趋势折线图**：贝塞尔曲线平滑 + 渐变色填充 + 触摸 Tooltip
- **分类支出占比环形图**：点击扇区联动底部排名列表
- 分类排行 Top3（进度条可视化）
- 环比对比（较上月增减百分比）

### 账单管理
- 按月分组展示（每日聚合 + 单笔明细）
- 高级搜索（金额/备注/分类关键词）
- 分类筛选（多选过滤）
- 左滑删除（确认弹窗防误操作）
- 点击编辑（修改全部字段）

### 个人设置
- 预算查看与修改
- 夜间模式切换（深紫 / 深色两种主题）
- 清空全部数据（二次确认）

---

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                    View Layer (4 Fragments)               │
│   HomeFragment  │  StatsFragment  │  BillFragment  │  ProfileFragment  │
├─────────────────────────────────────────────────────────┤
│               ViewModel Layer (StateFlow)                │
│   HomeViewModel (Activity-scoped, shared)  │  BillViewModel              │
├─────────────────────────────────────────────────────────┤
│                  Repository Layer                        │
│              AccountRepository (统一计算入口)              │
├─────────────────────────────────────────────────────────┤
│                    Data Layer                            │
│          Room Database (AccountDao + BudgetDao)          │
│              SQLite (account 表 + budget 表)             │
└─────────────────────────────────────────────────────────┘
```

### 语音识别 → 语义解析流水线

```
录音 (16kHz PCM) → Vosk TDNN 声学模型 → 文本
                                         ↓
                    HanLP 分词 → 三级金额提取 → 分类匹配 → 时间解析 → 备注提取
                                         ↓
                              结构化 JSON → 用户确认 → Room 持久化
```

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| 语言 | Kotlin |
| 架构 | MVVM + Repository |
| 响应式 | Kotlin StateFlow / Coroutines |
| 数据库 | Room (SQLite) v3 |
| 语音识别 | Vosk (vosk-model-small-cn-0.22, 离线 TDNN 声学模型) |
| 中文 NLP | HanLP portable-1.8.6 |
| UI 框架 | ViewPager2 + Fragment + Canvas 自绘 |
| 弹窗 | 自定义 GlassDialog（29 个弹窗统一风格） |
| 图表 | LineChartView / DonutChartView / BudgetRingView（Canvas 自绘） |
| 动画 | ValueAnimator / ViewPager2 PageTransformer |

## 代码结构

```
app/src/main/java/com/voice/accountbook/
├── MainActivity.kt              # 主 Activity（ViewPager2 页面切换）
├── database/
│   └── AccountDatabase.kt       # Room 数据库（v3）
├── dao/
│   ├── AccountDao.kt            # 账单流水 DAO
│   └── BudgetDao.kt             # 月度预算 DAO
├── entity/
│   ├── AccountBean.kt           # 账单实体（收入/支出）
│   └── BudgetEntity.kt          # 预算实体（yearMonth 为主键）
├── repository/
│   └── AccountRepository.kt     # 统一数据仓库（所有计算唯一入口）
├── viewmodel/
│   ├── HomeViewModel.kt         # 首页/统计/设置共享 ViewModel（Activity 作用域）
│   └── BillViewModel.kt         # 账单页 ViewModel
├── fragment/
│   ├── HomeFragment.kt          # 首页（语音录音 + 预算环形图 + 今日额度）
│   ├── StatsFragment.kt         # 统计页（折线图 + 环形图 + 排行榜）
│   ├── BillFragment.kt          # 账单页（搜索 + 筛选 + 滑动删除）
│   └── ProfileFragment.kt       # 我的页（预算设置 + 主题 + 数据管理）
├── adapter/
│   └── BillDayGroupAdapter.kt   # 账单列表按天分组适配器
├── view/
│   ├── LineChartView.kt         # Canvas 日收支趋势折线图
│   ├── DonutChartView.kt        # Canvas 分类支出环形图
│   └── BudgetRingView.kt        # Canvas 预算环形进度条
├── util/
│   ├── CategoryIconHelper.kt    # 分类图标映射工具
│   └── LocalLLMManager.kt       # 语音识别 + 语义解析引擎
└── res/
    ├── values/
    │   ├── colors.xml           # 深紫黑渐变配色方案
    │   └── themes.xml           # GlassDialog 透明磨砂主题
    ├── drawable/                # 磨砂玻璃背景 + 矢量分类图标（14 个支出 + 4 个收入）
    ├── layout/                  # 4 个 Fragment 布局 + MainActivity 布局
    └── anim/                    # ViewPager2 淡入淡出转场动画
```

## 构建与运行

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- AGP 8.2.0+
- Gradle 8.4+
- JDK 17
- Android SDK API 34

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/wclaa/voiceapp.git

# 2. 用 Android Studio 打开项目根目录

# 3. Sync Gradle，等待依赖下载完成

# 4. 连接设备或启动模拟器（API 24+）

# 5. 点击 Run 运行
```

### 依赖说明

| 依赖 | 用途 |
|------|------|
| `com.alphacephei:vosk-android:0.3.47` | 离线语音识别引擎 |
| `com.hankcs:hanlp:portable-1.8.6` | 中文分词与词性标注 |
| `androidx.room:room-runtime:2.6.1` | 本地 SQLite 数据库 |
| `androidx.room:room-ktx:2.6.1` | Room Kotlin 协程扩展 |
| `androidx.viewpager2:viewpager2:1.0.0` | 页面滑动切换 |

### 语音模型

项目已内置 Vosk 中文轻量模型（`vosk-model-small-cn-0.22`，约 42MB），位于 `app/src/main/assets/vosk-model-small-cn-0.22/`，无需额外下载。

---

## 链接

- **GitHub 仓库**: [https://github.com/wclaa/voiceapp](https://github.com/wclaa/voiceapp)

---

## License

MIT License
