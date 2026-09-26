package org.example.statemind.ui.page

import org.example.statemind.ui.SectionPage
import org.example.statemind.ui.page.setting.SettingInstancePage
import org.example.statemind.ui.page.setting.SettingLaunchPage
import org.example.statemind.ui.page.setting.SettingPlayerPage

/**
 * 设置。左边一列二级 tab，右边是各子页面。
 *
 * 用 [SectionPage] 而不是普通 Page —— 二级导航 + 子页面容器都由它统一搭好。
 */
class SettingPage : SectionPage(
    id = "setting",
    title = "设置",
    sections = listOf(SettingLaunchPage(), SettingPlayerPage(), SettingInstancePage())
)
