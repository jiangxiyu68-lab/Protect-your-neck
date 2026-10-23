package com.example.cervicalguard

import android.app.Application
import com.example.cervicalguard.data.Appearance

/**
 * Application：只做一件事——在**任何 Activity 创建之前**把深色/浅色偏好应用上。
 *
 * 为什么必须放在 Application 而不是 MainActivity：
 * 衣橱页（WardrobeActivity）是独立 Activity，它也要拿到正确的深浅色；
 * 而且 AppCompatDelegate 的 night mode 是进程级全局状态，在这里设一次最省事，
 * 不会出现"主界面深色、衣橱页浅色"这种割裂。
 */
class CgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Appearance.applyOnStartup(this)
    }
}
