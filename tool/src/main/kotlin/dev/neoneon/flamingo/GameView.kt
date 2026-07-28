package dev.neoneon.flamingo

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import dev.neoneon.chesskit.Board
import dev.neoneon.chesskit.Move
import dev.neoneon.chesskit.Piece
import dev.neoneon.chesskit.Position
import dev.neoneon.chesskit.Square
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "GameView"

class GameViewViewModel(
    val gameId: String,
    private val identityStore: PlayerIdentityStore,
    // The color this device plays. A seed only: it's what "New game" (white) vs.
    // "Accept invite" (black) opened the screen as, and is overridden by the
    // server's record once the game is fetched (see loadExistingGame).
    initialColor: Piece.Color = Piece.Color.white,
) : LightViewModel<Unit>() {
    private var board = Board()
    private val api = FlamingoApi()
    private val transport: LiveTransport = KtorLiveTransport(viewModelScope)
    private var moveCount = 0
    private var hasLoadedInitialState = false

    /** The invite-phrase share overlay's state, layered over the board on demand. */
    sealed interface Share {
        data object Hidden : Share
        data object Loading : Share
        data class Shown(val phrase: String) : Share
        data class Error(val message: String) : Share
    }

    data class State(
        val position: Position,
        val localColor: Piece.Color,
        val selectedSquare: Square? = null,
        val legalTargets: Set<Square> = emptySet(),
        val isLoading: Boolean = true,
        val playerId: String? = null,
        val moveCount: Int = 0,
        // Seat occupancy from the server record. A null seat means the game is still open for a
        // friend to join, so it can be shared; both stay null until the first load completes.
        val whitePlayerId: String? = null,
        val blackPlayerId: String? = null,
        val share: Share = Share.Hidden,
    ) {
        /** Only a game with an unfilled seat has anyone left to invite. */
        val hasOpenSeat: Boolean get() = whitePlayerId == null || blackPlayerId == null
    }

    private val _state = MutableStateFlow(State(position = board.position, localColor = initialColor))
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!hasLoadedInitialState) {
            hasLoadedInitialState = true
            observeLiveEvents()
            // Connect first so the socket is live before taps are enabled (the board stays
            // in its "Loading…" state, which blocks taps, until loadExistingGame finishes) —
            // this guarantees even white's first move goes out over the socket.
            viewModelScope.launch(Dispatchers.IO) {
                connect()
                loadExistingGame()
            }
        } else {
            // Returning to foreground: if we were waiting for the opponent, a move may have
            // landed while we were backgrounded — re-sync before reconnecting the socket.
            viewModelScope.launch(Dispatchers.IO) {
                if (isWaitingForOpponent()) loadExistingGame()
                connect()
            }
        }
    }

    override fun onAppPause() {
        super.onAppPause()
        // Drop the live connection while backgrounded; it's re-established on the next show.
        viewModelScope.launch { transport.disconnect() }
    }

    // Loads and replays this game's recorded moves so resuming an in-progress game
    // continues from its real current position instead of the initial one. A brand
    // new game has no server record yet, so a failure here just means "start fresh".
    // Safe to call again (foreground re-sync): the board is rebuilt from scratch.
    private suspend fun loadExistingGame() {
        val playerId = identityStore.getOrCreate()
        // Keep the seeded color / last-known seats until the server tells us otherwise (a
        // transient fetch failure shouldn't clobber what we already knew, e.g. flip an
        // already-filled game back to "shareable").
        var resolvedColor = _state.value.localColor
        var whiteId: String? = _state.value.whitePlayerId
        var blackId: String? = _state.value.blackPlayerId
        api.fetchGame(gameId).onSuccess { detail ->
            val sorted = detail.moves.sortedBy { it.moveNumber }
            board = Board()
            sorted.forEach { replayMove(it) }
            moveCount = sorted.maxOfOrNull { it.moveNumber } ?: 0
            whiteId = detail.game.whitePlayerID
            blackId = detail.game.blackPlayerID
            // The record is authoritative: we're white iff we're the white player, else
            // black (a black creator, or a not-yet-joined invitee whose seat is still open).
            resolvedColor =
                if (samePlayer(playerId, detail.game.whitePlayerID)) Piece.Color.white else Piece.Color.black
        }.onFailure { error ->
            Log.w(TAG, "No existing state loaded for game $gameId, starting fresh", error)
        }
        _state.update {
            it.copy(
                position = board.position,
                localColor = resolvedColor,
                isLoading = false,
                playerId = playerId,
                moveCount = moveCount,
                whitePlayerId = whiteId,
                blackPlayerId = blackId,
            )
        }
    }

    private suspend fun connect() {
        val playerId = _state.value.playerId ?: identityStore.getOrCreate()
        transport.connect(gameId, playerId)
    }

    // Waiting for the opponent means it's not our turn — i.e. the side to move
    // isn't the color this device plays.
    private fun isWaitingForOpponent(): Boolean =
        _state.value.position.sideToMove != _state.value.localColor

    // Collects inbound live actions for the lifetime of the screen. Runs on the main
    // dispatcher, the same context as onSquareTapped, so board mutations stay serialized.
    private fun observeLiveEvents() {
        viewModelScope.launch {
            transport.events.collect { event ->
                when (event) {
                    is LiveEvent.Action -> applyIncoming(event.action)
                    LiveEvent.NeedsResync -> {
                        // Divergence, or the socket dropped — re-sync from the server, then
                        // reconnect (after a short backoff to avoid hammering a bad connection).
                        loadExistingGame()
                        delay(1000)
                        connect()
                    }
                }
            }
        }
    }

    // Applies the opponent's live move to the board so we see it appear immediately.
    // The server never echoes our own moves back, so this only ever runs for the
    // opponent's moves — whichever color that is.
    private fun applyIncoming(action: LiveAction) {
        if (action.intent != LiveAction.INTENT_MOVE) return
        val lan = action.lan ?: return
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return
        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                board.completePromotion(move, kind)
            }
        }
        action.n?.let { if (it > moveCount) moveCount = it }
        _state.update {
            it.copy(
                position = board.position,
                selectedSquare = null,
                legalTargets = emptySet(),
                moveCount = moveCount,
            )
        }
    }

    // Applies a previously recorded move's LAN to [board], completing promotions
    // where needed. Draw-offer/resign log entries carry no LAN and are skipped.
    private fun replayMove(stored: dev.neoneon.flamingo.Move) {
        val lan = stored.lan ?: return   // draw/resign log entries carry no LAN
        if (lan.length < 4) return

        val from = Square(lan.substring(0, 2))
        val to = Square(lan.substring(2, 4))
        val move = board.move(pieceAt = from, to = to) ?: return

        if (lan.length == 5 && board.state is Board.State.promotion) {
            Piece.Kind.fromRawValue(lan.last().uppercase())?.let { kind ->
                board.completePromotion(move, kind)
            }
        }
    }

    // Taps only move our own pieces: they're ignored unless it's this device's
    // color to move, so the opponent's turn can never be played locally.
    fun onSquareTapped(square: Square) {
        _state.update { current ->
            val selected = current.selectedSquare

            when {
                current.isLoading || current.position.sideToMove != current.localColor -> current

                selected == square -> current.copy(selectedSquare = null, legalTargets = emptySet())

                selected != null && square in current.legalTargets -> {
                    val preMoveFen = board.position.fen
                    val move = board.move(pieceAt = selected, to = square)
                    if (move != null) submitMove(move, preMoveFen, current.playerId)
                    current.copy(
                        position = board.position,
                        selectedSquare = null,
                        legalTargets = emptySet(),
                        moveCount = moveCount,
                    )
                }

                current.position.piece(at = square)?.color == current.position.sideToMove -> {
                    current.copy(
                        selectedSquare = square,
                        legalTargets = board.legalMoves(forPieceAt = square).toSet(),
                    )
                }

                else -> current.copy(selectedSquare = null, legalTargets = emptySet())
            }
        }
    }

    // Sends the local move over the live socket instead of a separate HTTP request.
    // The frame carries only the acting player's id, so it works the same whether we
    // play white or black: the server records the move (creating the game lazily on
    // white's move 1) and broadcasts it to the opponent. The pre-move `fen` is sent so
    // the server stores the FEN each move was played *from*, matching the existing protocol.
    private fun submitMove(move: Move, preMoveFen: String, playerId: String?) {
        moveCount += 1
        val moveNumber = moveCount

        viewModelScope.launch(Dispatchers.IO) {
            val callerPlayerId = playerId ?: identityStore.getOrCreate()
            transport.send(
                LiveAction(
                    intent = LiveAction.INTENT_MOVE,
                    player = callerPlayerId,
                    lan = move.lan,
                    fen = preMoveFen,
                    n = moveNumber,
                )
            )
        }
    }

    // Opens the share overlay and fetches the phrase from the server (which re-mints if the
    // old one expired), so we never persist it locally. Only meaningful while a seat is open.
    fun openShare() {
        _state.update { it.copy(share = Share.Loading) }
        viewModelScope.launch(Dispatchers.IO) {
            api.shareInvite(gameId).fold(
                onSuccess = { response -> _state.update { it.copy(share = Share.Shown(response.phrase)) } },
                onFailure = { error ->
                    _state.update { it.copy(share = Share.Error(error.message ?: "Couldn't get code")) }
                },
            )
        }
    }

    fun closeShare() {
        _state.update { it.copy(share = Share.Hidden) }
    }

    override fun onCleared() {
        super.onCleared()
        transport.close()
        api.close()
    }
}

class GameView(
    sealedActivity: SealedLightActivity,
    private val gameId: String,
    // white when created via "New game", black when opened from an accepted invite;
    // reconciled with the server's record once the game loads.
    private val initialColor: Piece.Color = Piece.Color.white,
) : LightScreen<Unit, GameViewViewModel>(sealedActivity) {

    override val viewModelClass: Class<GameViewViewModel>
        get() = GameViewViewModel::class.java

    override fun createViewModel() =
        GameViewViewModel(gameId, PlayerIdentityStore(lightContext.dataStore), initialColor)

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
                when (val share = state.share) {
                    GameViewViewModel.Share.Hidden -> GameContent(state)
                    else -> SharePane(share = share, onClose = { viewModel.closeShare() })
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.GameContent(state: GameViewViewModel.State) {
        // It's the opponent's turn whenever the side to move isn't the color this device plays.
        // Surfaced as a second line in the nav bar rather than a dedicated strip, so it costs no
        // vertical space of its own (the bar reserves its height regardless).
        val waiting = !state.isLoading && state.position.sideToMove != state.localColor

        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = { goBack() },
                contentDescription = "Back to games",
            ),
            center = if (waiting) {
                LightTopBarCenter.TwoLineDetail(line1 = "Game", line2 = "Waiting for opponent…")
            } else {
                LightTopBarCenter.Text("Game")
            },
            // Offer the phrase only while a seat is still open — once both players are in,
            // there's no one left to invite.
            rightButton = if (!state.isLoading && state.hasOpenSeat) {
                LightBarButton.LightIcon(
                    icon = LightIcons.SEND,
                    onClick = { viewModel.openShare() },
                    contentDescription = "Share invite code",
                )
            } else {
                null
            },
        )

        // Size the board to the smaller of the available width/height so the full 8×8 always
        // fits below the nav bar, whatever the device's proportions — the board no longer
        // assumes it can be a full-screen-width square. Pin it to the bottom so any slack
        // falls as flexible breathing room between the board and the nav bar.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (state.isLoading) {
                LightText(
                    text = "Loading…",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                // Tapping a square is a no-op while it's not our turn
                // (GameViewViewModel.onSquareTapped guards on sideToMove),
                // so the board can stay on screen instead of being swapped for text.
                ChessBoard(
                    position = state.position,
                    selectedSquare = state.selectedSquare,
                    legalTargets = state.legalTargets,
                    onSquareTap = { viewModel.onSquareTapped(it) },
                    orientation = state.localColor,
                    boardSize = minOf(maxWidth, maxHeight),
                )
            }
        }
    }

    // The share overlay, shown in place of the board while [share] is non-Hidden. Reuses the
    // same phrase panel as the create flow (see SharePhraseContent).
    @Composable
    private fun ColumnScope.SharePane(share: GameViewViewModel.Share, onClose: () -> Unit) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back to game",
            ),
            center = LightTopBarCenter.Text("Invite"),
        )
        when (share) {
            GameViewViewModel.Share.Loading -> CenteredMessage("Getting code…")
            is GameViewViewModel.Share.Shown -> SharePhraseContent(
                phrase = share.phrase,
                buttonLabel = "BACK TO GAME",
                onDone = onClose,
            )
            is GameViewViewModel.Share.Error -> CenteredMessage(share.message)
            GameViewViewModel.Share.Hidden -> Unit // never rendered by SharePane
        }
    }

    @Composable
    private fun ColumnScope.CenteredMessage(text: String) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = text,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }
}
