package com.swordfish.lemuroid.app.mobile.feature.input

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.input.InputKey
import com.swordfish.lemuroid.app.shared.input.ShortcutBindingUpdater
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import timber.log.Timber
import javax.inject.Inject

class GamePadShortcutBindingActivity : RetrogradeActivity() {
    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    private lateinit var shortcutBindingUpdater: ShortcutBindingUpdater
    private val capturedKeysState = mutableStateOf<List<Int>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shortcutBindingUpdater =
            ShortcutBindingUpdater(inputDeviceManager, intent) { keys ->
                capturedKeysState.value = keys
            }

        setContent {
            AppTheme {
                val focusRequester = remember { FocusRequester() }
                val keys = capturedKeysState.value
                val keysLabel = keys.joinToString(" + ") { InputKey(it).displayName() }

                AlertDialog(
                    modifier =
                        Modifier
                            .focusRequester(focusRequester)
                            .focusable()
                            .onPreviewKeyEvent { handleKeyEvent(it.nativeKeyEvent) }
                            .onGloballyPositioned { focusRequester.requestFocus() },
                    title = { Text(text = shortcutBindingUpdater.getTitle(applicationContext)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = shortcutBindingUpdater.getMessage(applicationContext))
                            if (keys.size == 1) {
                                Text(
                                    text = stringResource(R.string.shortcut_binding_pressed, "$keysLabel + …"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (keys.size >= 2) {
                                Text(
                                    text = stringResource(R.string.shortcut_binding_release_to_save, keysLabel),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onDismissRequest = { finish() },
                    confirmButton = {
                        TextButton(
                            modifier = Modifier.focusProperties { canFocus = false },
                            onClick = {
                                shortcutBindingUpdater.clearBinding()
                                finish()
                            },
                        ) {
                            Text(text = stringResource(R.string.shortcut_binding_clear))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            modifier = Modifier.focusProperties { canFocus = false },
                            onClick = { finish() },
                        ) {
                            Text(text = stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            (event.device?.sources?.and(InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD)
        ) {
            return false
        }
        Timber.i("Received shortcut binding key event: $event")
        val completed = shortcutBindingUpdater.handleKeyEvent(event)
        if (shortcutBindingUpdater.isCompleted) {
            finish()
            return true
        }
        return completed || (event.device?.name == shortcutBindingUpdater.extras.device.name)
    }

    @dagger.Module
    abstract class Module
}
