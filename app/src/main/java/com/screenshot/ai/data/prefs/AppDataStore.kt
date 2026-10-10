package com.screenshot.ai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 应用级 DataStore 单例。
 *
 * 为什么单独放一个文件：`preferencesDataStore` 委托是按「属性」缓存单例的，
 * 如果在两个文件里各写一个同名扩展属性，会得到两个 DataStore 实例同时操作
 * 同一个文件，产生「后写覆盖先写」的并发问题。因此全局只允许这一个入口，
 * 需要读写的仓库（SettingsRepository / CustomModelRepositoryImpl）都引用它。
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "screenshot_ai_settings",
)
