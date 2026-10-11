package com.androidperformancestudio.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.onSecondaryClick(onSecondaryClick: () -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (
                    change.type == PointerType.Mouse &&
                    event.button == PointerButton.Secondary &&
                    change.pressed &&
                    !change.previousPressed
                ) {
                    onSecondaryClick()
                }
            }
        }
    },
)
