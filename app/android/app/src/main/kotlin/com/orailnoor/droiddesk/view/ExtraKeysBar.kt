package com.orailnoor.droiddesk.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.x11.LorieView

/**
 * Row of keys a phone keyboard lacks (Esc, Tab, Ctrl, Alt, arrows, ...), shown above the IME.
 *
 * Ctrl and Alt are sticky: one tap holds them for the next key, a second tap locks them,
 * a third releases. Other keys stay pressed while touched so X11 auto-repeat works.
 */
@SuppressLint("ViewConstructor")
class ExtraKeysBar(
    context: Context,
    private val lorieView: () -> LorieView?,
) : HorizontalScrollView(context) {

    private sealed class Key(val label: String) {
        class Code(label: String, val keyCode: Int) : Key(label)
        class Text(label: String, val text: String) : Key(label)
        class Modifier(label: String, val keyCode: Int) : Key(label)
    }

    private enum class Sticky { OFF, ONCE, LOCKED }

    private inner class ModifierState(val keyCode: Int, val view: TextView) {
        var state = Sticky.OFF
            set(value) {
                field = value
                styleKey(view, value)
            }
    }

    private val keys = listOf(
        Key.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
        Key.Code("TAB", KeyEvent.KEYCODE_TAB),
        Key.Modifier("CTRL", KeyEvent.KEYCODE_CTRL_LEFT),
        Key.Modifier("ALT", KeyEvent.KEYCODE_ALT_LEFT),
        Key.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
        Key.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        Key.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
        Key.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        Key.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        Key.Code("END", KeyEvent.KEYCODE_MOVE_END),
        Key.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
        Key.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
        Key.Text("-", "-"),
        Key.Text("/", "/"),
        Key.Text("|", "|"),
    )

    private val modifiers = mutableListOf<ModifierState>()
    private val density = resources.displayMetrics.density

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        setBackgroundColor(Color.argb(240, 20, 26, 36))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        keys.forEach { key -> row.addView(createKey(key), keyLayoutParams()) }
        addView(row)
    }

    /** Releases one-shot modifiers after the user typed a key; called from the IME input path. */
    fun onKeyTyped() {
        modifiers.filter { it.state == Sticky.ONCE }.forEach { release(it) }
    }

    /** Releases every held modifier, e.g. when the keyboard is closed. */
    fun releaseAll() {
        modifiers.filter { it.state != Sticky.OFF }.forEach { release(it) }
    }

    private fun createKey(key: Key): TextView = TextView(context).apply {
        text = key.label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
        styleKey(this, Sticky.OFF)
        contentDescription = key.label
        when (key) {
            is Key.Modifier -> {
                val modifier = ModifierState(key.keyCode, this)
                modifiers += modifier
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    cycle(modifier)
                }
            }
            is Key.Code -> setOnTouchListener { view, event -> handleCodeTouch(view, event, key.keyCode) }
            is Key.Text -> setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                lorieView()?.sendTextEvent(key.text.toByteArray(Charsets.UTF_8))
                onKeyTyped()
            }
        }
    }

    /** Holds the key down for as long as it is touched so the X server repeats it. */
    private fun handleCodeTouch(view: android.view.View, event: MotionEvent, keyCode: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                lorieView()?.sendKeyEvent(0, keyCode, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                lorieView()?.sendKeyEvent(0, keyCode, false)
                onKeyTyped()
            }
        }
        return true
    }

    private fun cycle(modifier: ModifierState) {
        when (modifier.state) {
            Sticky.OFF -> {
                lorieView()?.sendKeyEvent(0, modifier.keyCode, true)
                modifier.state = Sticky.ONCE
            }
            Sticky.ONCE -> modifier.state = Sticky.LOCKED
            Sticky.LOCKED -> release(modifier)
        }
    }

    private fun release(modifier: ModifierState) {
        lorieView()?.sendKeyEvent(0, modifier.keyCode, false)
        modifier.state = Sticky.OFF
    }

    private fun styleKey(view: TextView, state: Sticky) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(
                when (state) {
                    Sticky.OFF -> Color.argb(255, 40, 50, 66)
                    Sticky.ONCE -> Color.argb(255, 38, 110, 200)
                    Sticky.LOCKED -> Color.argb(255, 20, 150, 110)
                },
            )
        }
        view.setTextColor(Color.WHITE)
        view.paintFlags = if (state == Sticky.LOCKED) {
            view.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        } else {
            view.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun keyLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        dp(38),
    ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    private fun dp(value: Int) = (value * density).toInt()
}
