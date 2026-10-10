package com.screenshot.ai.data

import com.screenshot.ai.data.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设指令的纯逻辑测试（3.1.0：内置指令已全部移除）。
 *
 * [com.screenshot.ai.data.repo.PresetRepositoryImpl] 依赖 Room 的 DAO，
 * 在 JVM 单测里需要 Robolectric 或仪器测试才能跑，成本很高；
 * 这里守住「无内置指令」的核心数据不变式与 defaultOf 的选择规则。
 */
class PresetRepositoryLogicTest {

    @Test
    fun `3_1_0起不再提供任何内置预设`() {
        // 内置指令已全部移除：模型层没有 BUILT_IN 列表（编译期保证），
        // 新安装初始化即空列表（由仓库层保证不 seed）
        val fresh = Preset(id = "fresh", name = "新建", prompt = "p")
        assertFalse("新数据不应带内置标记", fresh.builtin)
        assertNull("空列表默认指令为 null（无内置兜底）", Preset.defaultOf(emptyList()))
    }

    @Test
    fun `新预设默认不是内置项且无默认标记`() {
        val custom = Preset(id = "custom_1", name = "自定义", prompt = "请描述这张截图")
        assertFalse(custom.isDefault)
        assertFalse(custom.builtin)
        assertNull("图标可以不填", custom.icon)
    }

    @Test
    fun `defaultOf空列表返回null`() {
        assertNull(Preset.defaultOf(emptyList()))
    }

    @Test
    fun `defaultOf优先返回带默认标记的项`() {
        val first = Preset(id = "a", name = "A", prompt = "pa")
        val flagged = Preset(id = "b", name = "B", prompt = "pb", isDefault = true)
        val last = Preset(id = "c", name = "C", prompt = "pc")

        val result = Preset.defaultOf(listOf(first, flagged, last))
        assertEquals("被标记的项优先，而不是取第一条", "b", result?.id)
    }

    @Test
    fun `defaultOf没有标记时回退到第一条`() {
        val first = Preset(id = "a", name = "A", prompt = "pa")
        val second = Preset(id = "b", name = "B", prompt = "pb")

        val result = Preset.defaultOf(listOf(first, second))
        assertEquals("没有默认标记时应回退到第一条", "a", result?.id)
    }

    @Test
    fun `defaultOf只包含一条时返回它`() {
        val only = Preset(id = "only", name = "唯一", prompt = "p")
        assertEquals("only", Preset.defaultOf(listOf(only))?.id)
    }
}
