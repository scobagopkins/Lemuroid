package com.swordfish.lemuroid.app.shared.settings

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.input.InputKey

data class GameShortcut(
    val type: GameShortcutType,
    val keys: Set<Int>,
) {
    val name: String =
        if (keys.isEmpty()) {
            ""
        } else {
            keys.joinToString(" + ") { InputKey(it).displayName() }
        }

    companion object {
        fun getDefault(
            inputDevice: InputDevice,
            type: GameShortcutType,
        ): GameShortcut? {
            if (type != GameShortcutType.MENU) return null
            return if (inputDevice.hasKeys(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR).all { it }) {
                GameShortcut(keys = setOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR), type = type)
            } else if (inputDevice.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START).all { it }) {
                GameShortcut(keys = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START), type = type)
            } else {
                null
            }
        }
    }
}

enum class GameShortcutType {
    MENU,
    QUICK_LOAD,
    QUICK_SAVE,
    TOGGLE_FAST_FORWARD,
    ;

    fun displayName(): String =
        name.split('_').joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.uppercase() }
        }

    fun displayName(context: Context): String {
        val resId =
            when (this) {
                MENU -> R.string.shortcut_type_menu
                QUICK_LOAD -> R.string.shortcut_type_quick_load
                QUICK_SAVE -> R.string.shortcut_type_quick_save
                TOGGLE_FAST_FORWARD -> R.string.shortcut_type_toggle_fast_forward
            }
        return context.getString(resId)
    }
}
