package dev.neoneon.flamingo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The longest name the server accepts (`PlayerName.maxLength`). Enforced here too so the editor
 * simply stops taking characters rather than letting someone type a name that comes back 422.
 */
private const val MAX_NAME_LENGTH = 24

class AccountViewModel(
    private val identityStore: PlayerIdentityStore,
) : LightViewModel<Unit>() {
    private val api = FlamingoApi()

    sealed class State {
        data object Loading : State()

        /**
         * [name] is what we last knew the server to hold — the cache, or the value a successful
         * rename just wrote. [error] carries a failed save, cleared the moment another is started.
         */
        data class Ready(val name: String?, val error: String? = null) : State()

        /** Mid-save. The editor is closed by now, so the screen shows the name going out. */
        data object Saving : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** True while the editor should be open. Held here so it survives a configuration change. */
    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        load()
    }

    /**
     * Shows the cached name immediately, then refreshes from the server.
     *
     * The cache is what makes this screen usable on a phone that is often offline: it was written
     * at registration and after every rename, so it is only stale if the name was changed from
     * the iOS app since this tool last ran — which the refresh then repairs.
     */
    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val playerId = identityStore.getOrCreate()
            _state.value = State.Ready(identityStore.cachedName())
            identityStore.ensureRegistered(api, playerId)

            val fresh = api.fetchPlayerNames(listOf(playerId)).getOrNull()?.firstOrNull()
            if (fresh != null) {
                identityStore.cacheName(fresh.name)
                _state.value = State.Ready(fresh.name)
            } else {
                _state.value = State.Ready(identityStore.cachedName())
            }
        }
    }

    fun startEditing() {
        _editing.value = true
    }

    fun cancelEditing() {
        _editing.value = false
    }

    /**
     * Saves [raw] as this player's name.
     *
     * Trimmed here as well as on the server, so "  " never leaves the phone as a save attempt
     * and the name the cache holds matches the one the server stored byte for byte. A save that
     * fails leaves the previous name in place and says why — nothing is written locally until
     * the server has taken it.
     */
    fun save(raw: String) {
        val name = raw.trim()
        _editing.value = false
        if (name.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val previous = (_state.value as? State.Ready)?.name
            _state.value = State.Saving
            val playerId = identityStore.getOrCreate()
            api.renamePlayer(playerId, name).fold(
                onSuccess = { renamed ->
                    identityStore.cacheName(renamed.name)
                    _state.value = State.Ready(renamed.name)
                },
                onFailure = { error ->
                    _state.value = State.Ready(previous, error.message ?: "Couldn't save name")
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}

/**
 * This installation's display name — what opponents see beside their own in a game title.
 *
 * Reached from Info. Editing opens the SDK's full-screen text editor rather than an inline field:
 * LightOS puts the keyboard on the screen, so a name is typed on a page of its own the same way
 * an invite code is (see [JoinByPhraseScreen]).
 */
class AccountScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AccountViewModel>(sealedActivity) {

    override val viewModelClass: Class<AccountViewModel>
        get() = AccountViewModel::class.java

    override fun createViewModel() = AccountViewModel(PlayerIdentityStore(lightContext.dataStore))

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val editing by viewModel.editing.collectAsState()
        val textState = rememberTextFieldState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val currentName = (state as? AccountViewModel.State.Ready)?.name

        // Seed the editor with the name as it stands, so editing starts from it rather than from
        // an empty field. Keyed on the name too, so a refresh that lands while the editor is
        // closed is picked up the next time it opens.
        LaunchedEffect(editing, currentName) {
            if (!editing) return@LaunchedEffect
            val seed = currentName.orEmpty()
            val existing = textState.text.length
            textState.edit {
                replace(0, existing, seed)
                selection = TextRange(seed.length)
            }
        }

        LightTheme(colors = themeColors) {
            if (editing) {
                LightTextInputEditor(
                    title = "Your name",
                    state = textState,
                    onSubmit = { viewModel.save(it.toString().take(MAX_NAME_LENGTH)) },
                    onBack = { viewModel.cancelEditing() },
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    submitLabel = "SAVE",
                    singleLine = true,
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                ) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back to info",
                        ),
                        center = LightTopBarCenter.Text("Account"),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        LightTextField(
                            label = "Your name",
                            value = when (state) {
                                is AccountViewModel.State.Loading -> ""
                                is AccountViewModel.State.Saving -> "Saving…"
                                is AccountViewModel.State.Ready -> currentName.orEmpty()
                            },
                            placeholder = "Not set yet",
                            onClick = { viewModel.startEditing() },
                        )

                        (state as? AccountViewModel.State.Ready)?.error?.let { error ->
                            LightText(
                                text = error,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }

                        LightText(
                            text = "Your opponent sees this name on the games you play together.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )

                        // Said out loud because it is a one-way door. The signing key lives in
                        // this phone's hardware keystore and cannot be exported or backed up, so
                        // a replacement handset starts a new profile. Better here than as a
                        // support surprise.
                        LightText(
                            text = "This name is tied to this phone. A new phone starts fresh.",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
            }
        }
    }
}
