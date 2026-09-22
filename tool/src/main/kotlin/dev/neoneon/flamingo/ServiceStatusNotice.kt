package dev.neoneon.flamingo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The line above the games list when the service is restricted but still usable.
 *
 * Text, not a dialog: `degraded` and `readOnly` both leave the player with games they can still
 * play, and something they have to dismiss would stop them doing the thing that still works. The
 * wording comes from the status document, so it can describe the actual situation.
 */
@Composable
fun ServiceStatusNotice(
    message: ServiceStatus.Message,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp())
            .padding(bottom = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = message.title,
            variant = LightTextVariant.Copy,
        )
        LightText(
            text = message.body,
            variant = LightTextVariant.Detail,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
    }
}
