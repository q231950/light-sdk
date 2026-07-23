package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import dev.neoneon.chesskit.Piece
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CreateGameViewModel(
    private val identityStore: PlayerIdentityStore,
) : LightViewModel<NewGameDestination>() {
    private val api = FlamingoApi()

    sealed class State {
        /** Picking a color, optionally showing the error from a failed create attempt. */
        data class Choosing(val color: Piece.Color = Piece.Color.white, val error: String? = null) : State()

        /** The invite request is in flight. */
        data object Creating : State()

        /** Game created: show [phrase] to share, then open [gameId] as [color]. */
        data class Created(val gameId: String, val phrase: String, val color: Piece.Color) : State()
    }

    private val _state = MutableStateFlow<State>(State.Choosing())
    val state: StateFlow<State> = _state

    fun selectColor(color: Piece.Color) {
        val current = _state.value as? State.Choosing ?: return
        _state.value = current.copy(color = color, error = null)
    }

    fun create() {
        val color = (_state.value as? State.Choosing)?.color ?: return
        _state.value = State.Creating
        viewModelScope.launch(Dispatchers.IO) {
            val playerId = identityStore.getOrCreate()
            api.createInvite(
                whitePlayerId = playerId.takeIf { color == Piece.Color.white },
                blackPlayerId = playerId.takeIf { color == Piece.Color.black },
            ).fold(
                onSuccess = { response ->
                    _state.value = State.Created(response.gameID, response.phrase, color)
                },
                onFailure = { error ->
                    _state.value = State.Choosing(color = color, error = error.message ?: "Couldn't create game")
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

class CreateGameScreen(sealedActivity: SealedLightActivity) :
    LightScreen<NewGameDestination, CreateGameViewModel>(sealedActivity) {

    override val viewModelClass: Class<CreateGameViewModel>
        get() = CreateGameViewModel::class.java

    override fun createViewModel() = CreateGameViewModel(PlayerIdentityStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back to games",
                    ),
                    center = LightTopBarCenter.Text("New game"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                when (val current = state) {
                    is CreateGameViewModel.State.Choosing -> ChoosingContent(
                        selected = current.color,
                        error = current.error,
                        onSelect = { viewModel.selectColor(it) },
                        onCreate = { viewModel.create() },
                    )

                    CreateGameViewModel.State.Creating -> CenteredMessage("Creating game…")

                    is CreateGameViewModel.State.Created -> CreatedContent(
                        phrase = current.phrase,
                        onOk = { goBack(NewGameDestination(current.gameId, current.color)) },
                    )
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.ChoosingContent(
        selected: Piece.Color,
        error: String?,
        onSelect: (Piece.Color) -> Unit,
        onCreate: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "Play as",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            ColorOption(label = "White", color = Piece.Color.white, selected = selected, onSelect = onSelect)
            ColorOption(label = "Black", color = Piece.Color.black, selected = selected, onSelect = onSelect)

            if (error != null) {
                LightText(
                    text = error,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CREATE", onClick = onCreate),
            ),
        )
    }

    @Composable
    private fun ColumnScope.CreatedContent(phrase: String, onOk: () -> Unit) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
        ) {
            LightText(
                text = "Share this phrase so a friend can join:",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            LightText(
                text = phrase,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1f.gridUnitsAsDp()),
            )
            LightText(
                text = "They enter it under Join to start the game.",
                variant = LightTextVariant.Fine,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "OK", onClick = onOk),
            ),
        )
    }

    @Composable
    private fun ColumnScope.CenteredMessage(text: String) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = text, variant = LightTextVariant.Copy)
        }
    }
}

@Composable
private fun ColorOption(
    label: String,
    color: Piece.Color,
    selected: Piece.Color,
    onSelect: (Piece.Color) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable { onSelect(color) }
            .padding(vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = if (color == selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            contentDescription = null,
        )
        LightText(
            text = label,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
        )
    }
}
