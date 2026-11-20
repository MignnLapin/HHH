package com.hhh.floatingkey

import android.content.Context
import android.view.KeyEvent

object Prefs {

    private const val FILE_NAME = "floating_key_config"
    private const val KEY_KEY_CODE = "key_code"

    fun getKeyCode(context: Context): Int =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_KEY_CODE, KeyEvent.KEYCODE_A)

    fun setKeyCode(context: Context, keyCode: Int) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_KEY_CODE, keyCode)
            .apply()
    }
}
