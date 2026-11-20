package com.hhh.floatingkey

import android.view.KeyEvent

data class KeyOption(val code: Int, val label: String, val buttonLabel: String = label)

object Keys {

    val all: List<KeyOption> = buildList {
        ('A'..'Z').forEach { ch ->
            add(KeyOption(KeyEvent.KEYCODE_A + (ch - 'A'), ch.toString()))
        }
        ('0'..'9').forEach { ch ->
            add(KeyOption(KeyEvent.KEYCODE_0 + (ch - '0'), ch.toString()))
        }
        (1..12).forEach { n ->
            add(KeyOption(KeyEvent.KEYCODE_F1 + (n - 1), "F$n"))
        }
        add(KeyOption(KeyEvent.KEYCODE_ENTER, "Enter", "⏎"))
        add(KeyOption(KeyEvent.KEYCODE_SPACE, "Space", "Spc"))
        add(KeyOption(KeyEvent.KEYCODE_TAB, "Tab"))
        add(KeyOption(KeyEvent.KEYCODE_ESCAPE, "Esc"))
        add(KeyOption(KeyEvent.KEYCODE_DEL, "Backspace", "⌫"))
        add(KeyOption(KeyEvent.KEYCODE_FORWARD_DEL, "Delete", "Del"))
        add(KeyOption(KeyEvent.KEYCODE_DPAD_LEFT, "←"))
        add(KeyOption(KeyEvent.KEYCODE_DPAD_UP, "↑"))
        add(KeyOption(KeyEvent.KEYCODE_DPAD_RIGHT, "→"))
        add(KeyOption(KeyEvent.KEYCODE_DPAD_DOWN, "↓"))
        add(KeyOption(KeyEvent.KEYCODE_MOVE_HOME, "Home"))
        add(KeyOption(KeyEvent.KEYCODE_MOVE_END, "End"))
        add(KeyOption(KeyEvent.KEYCODE_PAGE_UP, "PageUp"))
        add(KeyOption(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown"))
        add(KeyOption(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift"))
        add(KeyOption(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl"))
        add(KeyOption(KeyEvent.KEYCODE_ALT_LEFT, "Alt"))
        add(KeyOption(KeyEvent.KEYCODE_CAPS_LOCK, "CapsLock"))
        add(KeyOption(KeyEvent.KEYCODE_NUM_LOCK, "NumLock"))
        add(KeyOption(KeyEvent.KEYCODE_PERIOD, "."))
        add(KeyOption(KeyEvent.KEYCODE_COMMA, ","))
        add(KeyOption(KeyEvent.KEYCODE_SLASH, "/"))
        add(KeyOption(KeyEvent.KEYCODE_BACKSLASH, "\\"))
        add(KeyOption(KeyEvent.KEYCODE_SEMICOLON, ";"))
        add(KeyOption(KeyEvent.KEYCODE_APOSTROPHE, "'"))
        add(KeyOption(KeyEvent.KEYCODE_GRAVE, "`"))
        add(KeyOption(KeyEvent.KEYCODE_MINUS, "-"))
        add(KeyOption(KeyEvent.KEYCODE_EQUALS, "="))
        add(KeyOption(KeyEvent.KEYCODE_LEFT_BRACKET, "["))
        add(KeyOption(KeyEvent.KEYCODE_RIGHT_BRACKET, "]"))
        add(KeyOption(KeyEvent.KEYCODE_PLUS, "+"))
        add(KeyOption(KeyEvent.KEYCODE_AT, "@"))
        add(KeyOption(KeyEvent.KEYCODE_POUND, "#"))
        add(KeyOption(KeyEvent.KEYCODE_STAR, "*"))
    }

    fun labelFor(keyCode: Int): String =
        all.firstOrNull { it.code == keyCode }?.label ?: KeyEvent.keyCodeToString(keyCode)

    fun buttonLabelFor(keyCode: Int): String =
        all.firstOrNull { it.code == keyCode }?.buttonLabel ?: "?"
}
