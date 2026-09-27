package dev.neoneon.flamingo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

@Composable
fun GameListRow(
    game: Game,
    // This device's player id, so the row can name the color we hold and say whose move it is.
    playerId: String?,
    // Display names keyed by player id, from `FlamingoApi.fetchPlayerNames`. Empty until they
    // arrive — [Game.title] falls back per seat, so the row draws either way.
    names: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(
            text = game.title(playerId, names),
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            // Status and last activity share the detail line rather than taking one each: this
            // screen is a list on a small display, and the two read as one thought — "your turn,
            // 2 hours ago". A game whose timestamp wouldn't parse just shows the status.
            text = listOfNotNull(game.statusLabel(playerId), game.lastActivity())
                .joinToString(" · "),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
