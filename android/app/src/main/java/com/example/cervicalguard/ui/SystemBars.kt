package com.example.cervicalguard.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.core.content.ContextCompat
import com.example.cervicalguard.R

/**
 * 状态栏 / 导航栏配色与图标明暗（v1.5 / v1.5.1）。
 *
 * 分工：
 *  - **主题里**（`values/themes.xml` + `values-night/themes.xml`）设 `windowLightStatusBar`，
 *    这样连**系统启动窗口阶段**（App 内容还没画出来）状态栏图标就是对的；
 *  - **这里**在 Activity 创建后把两栏底色和图标状态再对齐一次，保证运行时切换主题也一致。
 *
 * ⚠️ 导航栏不用 `LIGHT_NAVIGATION_BAR`：华为 EMUI 9.1 **忽略该 flag**
 * （验收专员实测：flag 已生效但图标仍是最暗 186 的浅灰白）。所以导航栏底色固定深色
 * （`@color/nav_bar`），白图标永远可见，不依赖厂商实现。
 */
object SystemBars {

    fun apply(activity: Activity) {
        val night = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        activity.window.statusBarColor = ContextCompat.getColor(activity, R.color.bg)
        activity.window.navigationBarColor = ContextCompat.getColor(activity, R.color.nav_bar)

        // 只设 LIGHT_STATUS_BAR（浅色 → 状态栏图标转深）；导航栏永远是深底浅图标
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!night && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = flags
    }
}
