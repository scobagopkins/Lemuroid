package com.swordfish.lemuroid.app.shared.input

import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.settings.GameShortcutType
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking

/**
 * Captures a two-button combo in press order (e.g., Select then A -> Select + A).
 * Saves once both buttons are released so the final KEY_UP never leaks to the screen below.
 */
@OptIn(DelicateCoroutinesApi::class)
class ShortcutBindingUpdater(
    private val inputDeviceManager: InputDeviceManager,
    intent: Intent,
    private val onCapturedKeysChanged: (List<Int>) -> Unit = {},
) {
    val extras = parseExtras(intent)

    private val capturedKeyCodes = mutableListOf<Int>()
    private val currentlyHeldKeys = mutableSetOf<Int>()

    var isCompleted: Boolean = false
        private set

    fun getTitle(context: Context): String {
        return context.getString(R.string.shortcut_binding_update_title, extras.shortcutType.displayName(context))
    }

    fun getMessage(context: Context): String {
        return context.getString(R.string.shortcut_binding_update_description, extras.device.name)
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onKeyDown(event)
            KeyEvent.ACTION_UP -> onKeyUp(event)
            else -> false
        }
    }

    fun clearBinding() {
        runBlocking {
            inputDeviceManager.clearShortcutBinding(extras.device, extras.shortcutType)
        }
        isCompleted = true
    }

    private fun onKeyDown(event: KeyEvent): Boolean {
        if (!isTargetedDevice(event.device) || isSystemIgnoredKey(event.keyCode)) return false
        if (event.repeatCount > 0) return true

        currentlyHeldKeys.add(event.keyCode)
        if (capturedKeyCodes.size < 2 && event.keyCode !in capturedKeyCodes) {
            capturedKeyCodes.add(event.keyCode)
            onCapturedKeysChanged(capturedKeyCodes.toList())
        }
        return true
    }

    private fun onKeyUp(event: KeyEvent): Boolean {
        if (!isTargetedDevice(event.device) || isSystemIgnoredKey(event.keyCode)) return false

        currentlyHeldKeys.remove(event.keyCode)
        if (capturedKeyCodes.size == 2 && currentlyHeldKeys.isEmpty() && !isCompleted) {
            val combo = Pair(InputKey(capturedKeyCodes[0]), InputKey(capturedKeyCodes[1]))
            runBlocking {
                inputDeviceManager.updateShortcutBinding(event.device, extras.shortcutType, combo)
            }
            isCompleted = true
            return true
        }
        return false
    }

    private fun isSystemIgnoredKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_UNKNOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_POWER ||
            keyCode == KeyEvent.KEYCODE_CAMERA
    }

    private fun isTargetedDevice(device: InputDevice?): Boolean {
        return device != null && extras.device.name == device.name
    }

    private fun parseExtras(intent: Intent): IntentExtras {
        val device =
            intent.extras?.getParcelable<InputDevice>(REQUEST_DEVICE)
                ?: throw IllegalArgumentException("REQUEST_DEVICE has not been passed")

        val shortcutType =
            intent.extras?.getString(REQUEST_SHORTCUT_TYPE)
                ?: throw IllegalArgumentException("REQUEST_SHORTCUT_TYPE has not been passed")

        return IntentExtras(device, GameShortcutType.valueOf(shortcutType))
    }

    data class IntentExtras(val device: InputDevice, val shortcutType: GameShortcutType)

    companion object {
        const val REQUEST_DEVICE = "REQUEST_DEVICE"
        const val REQUEST_SHORTCUT_TYPE = "REQUEST_SHORTCUT_TYPE"
    }
}
