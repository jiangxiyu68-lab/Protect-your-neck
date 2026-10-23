package com.example.cervicalguard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * 外观偏好（v1.5）：让用户自己选深色 / 浅色 / 跟随系统。
 *
 * 实现用 `AppCompatDelegate.setDefaultNightMode()`：
 *  - 深色 → 系统读 `values-night/colors.xml`
 *  - 浅色 → 读 `values/colors.xml`
 *  - 跟随系统 → MODE_NIGHT_FOLLOW_SYSTEM
 *
 * 注意：**没有做"运行时改色"**，而是整包资源切换，所以切换后 Activity 会重建一次。
 * 这样做的好处是弹窗、输入框、Toast、下拉菜单等系统控件也会一起变；
 * 如果只改自定义 View 的颜色，那些系统控件会在深色 App 里继续白底（旧版本的老毛病）。
 */
class Appearance(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        const val SYSTEM = "system"
        const val DARK = "dark"
        const val LIGHT = "light"

        /** 映射到 AppCompatDelegate 的 night mode 常量。 */
        fun toNightMode(value: String): Int = when (value) {
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        fun label(value: String): String = when (value) {
            DARK -> "深色"
            LIGHT -> "浅色"
            else -> "跟随系统"
        }

        /**
         * 在 Application.onCreate 里调一次，保证进程内所有 Activity
         * （主界面、衣橱页）一创建就拿到正确的深浅色。
         */
        fun applyOnStartup(context: Context) {
            AppCompatDelegate.setDefaultNightMode(toNightMode(Appearance(context).mode))
        }
    }

    /**
     * 当前模式。默认 [DARK]：这个 App 从 v1.0 起就是深色观感，
     * 升级上来的用户不应该某天突然变成白底（那是最容易被骂的"瞎改"）。
     */
    var mode: String
        get() = sp.getString("appearance", DARK) ?: DARK
        set(v) {
            val safe = if (v == SYSTEM || v == LIGHT || v == DARK) v else DARK
            sp.edit().putString("appearance", safe).apply()
            AppCompatDelegate.setDefaultNightMode(toNightMode(safe))
        }

    /** 切换并返回是否真的发生了变化（没变就不用重建界面）。 */
    fun setIfChanged(v: String): Boolean {
        if (v == mode) return false
        mode = v
        return true
    }
}
