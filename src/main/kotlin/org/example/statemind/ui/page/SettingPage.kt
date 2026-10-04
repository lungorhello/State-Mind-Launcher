package org.example.statemind.ui.page

import org.example.statemind.ui.SectionPage
import org.example.statemind.ui.page.setting.SettingInstancePage
import org.example.statemind.ui.page.setting.SettingLaunchPage
import org.example.statemind.ui.page.setting.SettingPlayerPage

class SettingPage : SectionPage(
    id = "setting",
    title = "设置",
    sections = listOf(SettingLaunchPage(), SettingPlayerPage(), SettingInstancePage())
)
