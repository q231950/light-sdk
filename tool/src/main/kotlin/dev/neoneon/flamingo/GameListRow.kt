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
    // This device's player id, so an in-progress game can say whose move it is.
    playerId: String?,
    // Display names keyed by player id, from `FlamingoApi.fetchPlayerNames`. Empty until they
    // arrive — [Game.title] falls back per seat, so the row draws either way.
    names: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LightText(
            text = game.title(names),
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = game.statusLabel(playerId),
            variant = LightTextVariant.Detail,
            lighten = true,
        )
    }
}
