package dev.neoneon.flamingo

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop

private const val UNDERLINE_THICKNESS_PX = 3f
private const val ACTIVE_UNDERLINE_THICKNESS_PX = 7f
private const val GLYPH_TO_UNDERLINE_GAP_GRID_UNITS = 0.5f
private const val BOX_GAP_GRID_UNITS = 1f

/**
 * Default normalization for a letter code: uppercase, keep only ASCII letters (dropping digits,
 * punctuation, whitespace, emoji), and cap at [length]. Matches the iOS `InviteCode.normalize`,
 * so a code read off any screen normalizes identically on both platforms.
 */
fun normalizeLetterCode(raw: String, length: Int): String =
    buildString {
        for (character in raw) {
            if (this.length >= length) break
            if (character in 'A'..'Z') append(character)
            else if (character in 'a'..'z') append(character - 32)
        }
    }

/**
 * OTP-style fixed-length code entry: one underlined box per character, backed by an embedded
 * LP3 keyboard. Convenience overload that builds the keyboard view-model for you; pass a
 * [keyboardOptionsFlow] from `rememberKeyboardOptions()`.
 *
 * Editing follows the platform's normal text model (the same key/Backspace/Return wiring the
 * SDK's own full-screen editor uses): keys insert, Backspace deletes, Return submits. A reactive
 * normalization pass then keeps [state] to [normalize]'s shape — for the default that means
 * uppercase ASCII letters, non-letters dropped, capped at [length] — and the boxes simply
 * mirror the result. This is the Compose analog of the iOS `CodeInputView`.
 *
 * [onComplete] fires once, the moment the code first reaches [length] characters (for
 * auto-submit); it does not fire for a code that is already full when the field appears.
 * [onSubmit] is the explicit action (bottom-bar button / Return) and may fire on a partial
 * code — the caller decides whether a short code is acceptable.
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
    normalize: (String) -> String = { normalizeLetterCode(it, length) },
    editorKey: Any = title,
) {
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val keyboardCallback = remember(state) {
        CodeInputKeyboardCallback(
            state = state,
            onReturn = { currentOnSubmit(state.text) },
        )
    }

    val keyboardViewModel: Lp3KeyboardViewModel<*> = viewModel<EnQwertyLp3KeyboardViewModel<*>>(
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
        onComplete = onComplete,
        normalize = normalize,
    )
}

/**
 * Full-screen code entry: top bar, a centered row of [length] underlined character boxes, the
 * embedded LP3 keyboard, and a [LightBottomBar] submit button.
 */
@Composable
fun LightCodeInput(
    title: String,
    state: TextFieldState,
    length: Int,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    viewModel: Lp3KeyboardViewModel<*>,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    onComplete: ((CharSequence) -> Unit)? = null,
    normalize: (String) -> String = { normalizeLetterCode(it, length) },
) {
    val currentOnComplete by rememberUpdatedState(onComplete)

    // Mirror the iOS `CodeInputView.onChange`: normalize the field's text, and once it settles
    // at full length fire onComplete exactly once. `drop(1)` skips the initial value so a code
    // preserved across a failed attempt doesn't auto-resubmit when the field reappears.
    LaunchedEffect(state, length) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect { raw ->
                val normalized = normalize(raw)
                if (normalized != raw) {
                    val current = state.text.length
                    state.edit {
                        replace(0, current, normalized)
                        selection = TextRange(normalized.length)
                    }
                    // The reassignment re-fires snapshotFlow; onComplete is handled on that
                    // settled pass (normalized == raw) so it fires exactly once.
                } else if (normalized.length == length) {
                    currentOnComplete?.invoke(normalized)
                }
            }
    }

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

/**
 * The row of underlined character boxes. Every box gets an equal share of the width (like the
 * iOS `CodeInputView`'s `.frame(maxWidth: .infinity)`), so the glyphs stay evenly spaced across
 * the screen regardless of how many are filled. The next empty box carries a thicker underline.
 */
@Composable
private fun CodeBoxes(text: String, length: Int) {
    val colors = LightThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BOX_GAP_GRID_UNITS.gridUnitsAsDp()),
    ) {
        for (index in 0 until length) {
            val glyph = if (index < text.length) text[index].toString() else null
            // The active box is the first empty one; once full, nothing is active.
            val isActive = index == text.length && text.length < length
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // A blank slot renders a space so every box keeps the same height when empty.
                LightText(
                    text = glyph ?: " ",
                    variant = LightTextVariant.Heading,
                    monospace = true,
                    align = TextAlign.Center,
                )
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
 * Wires an embedded LP3 keyboard to a single-line [TextFieldState]: keys insert at the cursor,
 * Backspace deletes (a whole surrogate pair or, on long-press, a whole word), Return submits.
 * The SDK's own editors have an equivalent, but it's `internal` to `sdk:ui`, so this is a
 * tool-local copy rather than a shared dependency.
 */
private class CodeInputKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBeforeCursor(surrogateAwareDeleteCount(before))
            }
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            val before = state.text.subSequence(0, state.selection.min)
            deleteBeforeCursor(deleteWordCount(before))
        }
    }

    override fun onKeyRepeated(code: Int) {
        insertCodePoint(code)
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) {
        insertAtCursor(word.toString())
    }

    private fun insertCodePoint(code: Int) {
        insertAtCursor(buildString { appendCodePoint(code) })
    }

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            val end = selection.max
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else 1
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}

private fun factory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptionsFlow,
                optionsForLayout = {
                    val showCloseButton = !it.isRootLayout
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
