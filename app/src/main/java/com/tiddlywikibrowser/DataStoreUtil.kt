package com.tiddlywikibrowser

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// Create a DataStore instance using property delegation
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tidweb_settings")

/**
 * Keys for DataStore preferences
 */
object PreferencesKeys {
    val WIKI_LIST = stringPreferencesKey("wiki_list")
    val CURRENT_WIKI = stringPreferencesKey("current_wiki")
    val IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    val FAVICONS = stringPreferencesKey("favicons")
    val QUICK_TAGS = stringPreferencesKey("quick_tags")
    val USE_SMALL_SCREEN_CSS = booleanPreferencesKey("use_small_screen_css")
}