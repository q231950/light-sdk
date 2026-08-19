package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import dev.neoneon.chesskit.Piece
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Invite codes are five letters (see the server's `InviteCode`). */
private const val CODE_LENGTH = 5

class JoinByPhraseViewModel(
    private val identityStore: PlayerIdentityStore,
) : LightViewModel<NewGameDestination>() {
    private val api = FlamingoApi()

    sealed class State {
        /** Entering the phrase; [error] surfaces a failed attempt (kept short for the top bar). */
        data class Input(val error: String? = null) : State()

        /** The join request is in flight. */
        data object Joining : State()

        /** Joined: open the game and play whichever seat we filled. */
        data class Joined(val destination: NewGameDestination) : State()
    }

    private val _state = MutableStateFlow<State>(State.Input())
    val state: StateFlow<State> = _state

    fun join(phrase: String) {
        val trimmed = phrase.trim()
        if (trimmed.length != CODE_LENGTH) {
            _state.value = State.Input("Enter the 5-letter code")
            return
        }
        _state.value = State.Joining
        viewModelScope.launch(Dispatchers.IO) {
            val playerId = identityStore.getOrCreate()
            api.joinByPhrase(trimmed, playerId).fold(
                onSuccess = { response ->
                    // The seat we filled decides our color: we're white only if the record now
                    // names us there, otherwise black (we joined a white creator's open black seat).
                    val color =
                        if (samePlayer(response.game.whitePlayerID, playerId)) Piece.Color.white else Piece.Color.black
                    _state.value = State.Joined(NewGameDestination(response.game.id, color))
                },
                onFailure = { error ->
                    _state.value = State.Input(error.message ?: "Join failed")
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class JoinByPhraseScreen(sealedActivity: SealedLightActivity) :
    LightScreen<NewGameDestination, JoinByPhraseViewModel>(sealedActivity) {

    override val viewModelClass: Class<JoinByPhraseViewModel>
        get() = JoinByPhraseViewModel::class.java

    override fun createViewModel() = JoinByPhraseViewModel(PlayerIdentityStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        // Held across the Input/Joining swap so a failed join leaves the typed phrase in place.
        val textState = rememberTextFieldState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state) {
            (state as? JoinByPhraseViewModel.State.Joined)?.let { goBack(it.destination) }
        }

        LightTheme(colors = themeColors) {
            when (val current = state) {
                is JoinByPhraseViewModel.State.Input -> LightCodeInput(
                    // No dedicated error slot, so the top-bar title carries the instruction
                    // normally and the (short) error after a failed attempt.
                    title = current.error ?: "Enter code",
                    state = textState,
                    length = CODE_LENGTH,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    submitLabel = "JOIN",
                    // Fire automatically the moment the fifth letter lands; the JOIN button /
                    // Return remain a manual fallback.
                    onComplete = { viewModel.join(it.toString()) },
                    onSubmit = { viewModel.join(it.toString()) },
                    onBack = { goBack() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                )

                JoinByPhraseViewModel.State.Joining,
                is JoinByPhraseViewModel.State.Joined ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(text = "Joining game…", variant = LightTextVariant.Copy)
                    }
            }
        }
    }
}
