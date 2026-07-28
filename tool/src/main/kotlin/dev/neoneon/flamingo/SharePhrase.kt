package dev.neoneon.flamingo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The invite-phrase share panel: instructions plus the [phrase] to read out, with a single
 * bottom-bar button labelled [buttonLabel] that calls [onDone]. Shared by the create flow
 * (shown once a game is minted) and the game view (re-opened on demand while a seat is open),
 * so both surfaces present the phrase identically.
 */
@Composable
fun ColumnScope.SharePhraseContent(phrase: String, buttonLabel: String, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = "Share this code so a friend can join:",
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
            LightBarButton.Text(text = buttonLabel, onClick = onDone),
        ),
    )
}
