package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val UNDERLINE_THICKNESS_PX = 3f
private const val ACTIVE_UNDERLINE_THICKNESS_PX = 7f
private const val GLYPH_TO_UNDERLINE_GAP_GRID_UNITS = 0.5f
private const val BOX_INNER_PADDING_GRID_UNITS = 0.5f
private const val BOX_GAP_GRID_UNITS = 1f

/**
 * OTP-style fixed-length code entry: one underlined box per character, backed by an
 * embedded LP3 keyboard. Convenience overload that builds the keyboard view-model for you;
 * pass a [keyboardOptionsFlow] from `rememberKeyboardOptions()`.
 *
 * Behaviour (linear fill + backspace):
 * - Each ASCII letter fills the next empty box (uppercased); non-letters are ignored.
 * - Backspace clears the last filled box and steps back; long-press Backspace clears all.
 * - [onComplete] fires once, the moment the final box fills (for auto-submit). [onSubmit]
 *   is the explicit action (bottom-bar button / Return) and may fire on a partial code —
 *   the caller decides whether a short code is acceptable.
 */
@Composable
fun LightCodeInput(
    title: String,
    state: TextFieldState,
    length: Int,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    onComplete: ((CharSequence) -> Unit)? = null,
    editorKey: Any = title,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnComplete by rememberUpdatedState(onComplete)
    val keyboardCallback = remember(state, length) {
        CodeKeyboardCallback(
            state = state,
            length = length,
            onComplete = { currentOnComplete?.invoke(it) },
            onReturn = { currentOnSubmit(state.text) },
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel = viewModel<DefaultLp3KeyboardViewModel>(
        key = "LightCodeInput-$editorKey",
        factory = factory(keyboardCallback, keyboardOptionsFlow),
    )

    LightCodeInput(
        title = title,
        state = state,
        length = length,
        onSubmit = onSubmit,
        onBack = onBack,
        viewModel = keyboardViewModel,
        modifier = modifier,
        submitLabel = submitLabel,
    )
}

/**
 * Full-screen code entry: top bar, a centered row of [length] underlined character boxes,
 * the embedded LP3 keyboard, and a [LightBottomBar] submit button.
 */
@Composable
fun LightCodeInput(
    title: String,
    state: TextFieldState,
    length: Int,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    viewModel: Lp3KeyboardViewModel,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
) {
    Surface {
        Column(modifier = modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                CodeBoxes(text = state.text.toString(), length = length)
            }

            LightEmbeddedLp3Keyboard(viewModel = viewModel)

            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        text = submitLabel,
                        onClick = { onSubmit(state.text) },
                    ),
                ),
            )
        }
    }
}

/** The row of underlined character boxes. The next empty box carries a thicker underline. */
@Composable
private fun CodeBoxes(text: String, length: Int) {
    val colors = LightThemeTokens.colors
    Row(horizontalArrangement = Arrangement.spacedBy(BOX_GAP_GRID_UNITS.gridUnitsAsDp())) {
        for (index in 0 until length) {
            val glyph = if (index < text.length) text[index].toString() else null
            // The active box is the first empty one; once full, nothing is active.
            val isActive = index == text.length && text.length < length
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.padding(horizontal = BOX_INNER_PADDING_GRID_UNITS.gridUnitsAsDp()),
                ) {
                    // A blank slot renders a space so every box has the same monospace advance width.
                    LightText(
                        text = glyph ?: " ",
                        variant = LightTextVariant.Heading,
                        monospace = true,
                        align = TextAlign.Center,
                    )
                }
                Spacer(modifier = Modifier.height(GLYPH_TO_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp()))
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            (if (isActive) ACTIVE_UNDERLINE_THICKNESS_PX else UNDERLINE_THICKNESS_PX)
                                .designVerticalPxToDp(),
                        )
                        .background(colors.content),
                )
            }
        }
    }
}

/**
 * Keyboard callback enforcing the linear fill + backspace behaviour for a fixed-length code.
 * Only ASCII letters are accepted (uppercased); everything else — digits, symbols, space,
 * emoji — is ignored. Held-letter repeats are dropped so one key can't run the row full.
 */
private class CodeKeyboardCallback(
    private val state: TextFieldState,
    private val length: Int,
    private val onComplete: (CharSequence) -> Unit,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit
    override fun onSpecialKeyPressed(specialKey: SpecialKey) = Unit

    override fun onKeyReleased(code: Int) = appendLetter(code)

    // Ignore held-letter repeats: a leaned-on key must not fill the remaining boxes.
    override fun onKeyRepeated(code: Int) = Unit

    override fun onSpecialKeyReleased(specialKey: SpecialKey) {
        when (specialKey) {
            SpecialKey.Backspace -> deleteLast()
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Backspace) deleteLast()
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Backspace) clearAll()
    }

    private fun appendLetter(code: Int) {
        val end = state.text.length
        if (end >= length) return
        val upper = asciiLetterUppercased(code) ?: return
        state.edit {
            replace(end, end, upper.toString())
            selection = TextRange(end + 1)
        }
        if (state.text.length == length) onComplete(state.text)
    }

    private fun deleteLast() {
        val end = state.text.length
        if (end == 0) return
        state.edit {
            delete(end - 1, end)
            selection = TextRange(end - 1)
        }
    }

    private fun clearAll() {
        val end = state.text.length
        if (end == 0) return
        state.edit {
            delete(0, end)
            selection = TextRange(0)
        }
    }

    /** `A–Z`/`a–z` code point → uppercase `Char`; anything else → null. */
    private fun asciiLetterUppercased(code: Int): Char? = when (code) {
        in 'A'.code..'Z'.code -> code.toChar()
        in 'a'.code..'z'.code -> (code - 32).toChar()
        else -> null
    }
}

private fun factory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DefaultLp3KeyboardViewModel(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = when (it) {
                        EmojiLayout, is ExtendedCharKeyboard -> true
                        CapsLockedLayout, LowerCaseLayout, NumberLayout, SymbolsLayout, UpperCaseLayout -> false
                    }
                    LayoutOptions(showCloseButton)
                },
            ) as T
        }
    }

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightCodeInputDark() {
    val state = rememberTextFieldState("AD")
    LightTheme(colors = LightThemeColors.Dark) {
        LightCodeInput(
            title = "Enter code",
            state = state,
            length = 5,
            keyboardOptionsFlow = MutableStateFlow(defaultKeyboardOptions()),
            onSubmit = {},
            onBack = {},
            submitLabel = "JOIN",
        )
    }
}
