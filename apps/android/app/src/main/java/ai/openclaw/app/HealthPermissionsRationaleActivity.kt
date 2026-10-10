package ai.openclaw.app

import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.ui.OpenClawTheme
import ai.openclaw.app.ui.design.ClawDesignTheme
import ai.openclaw.app.ui.design.ClawPrimaryButton
import ai.openclaw.app.ui.design.ClawTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Health Connect opens this screen from its permission sheet and from Android's privacy
 * settings. It must exist or Health Connect refuses to show the permission request.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      OpenClawTheme {
        ClawDesignTheme {
          Column(
            modifier =
              Modifier
                .fillMaxSize()
                .background(ClawTheme.colors.canvas)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Text(text = nativeString("How OpenClaw uses Health Connect"), style = ClawTheme.type.section, color = ClawTheme.colors.text)
            Text(
              text =
                nativeString(
                  "OpenClaw reads steps, sleep, heart rate, resting heart rate, and exercise sessions only when your paired Gateway asks for a health summary. The phone adds them up and sends only the totals for the requested days to your Gateway, which may pass them to your AI provider.",
                ),
              style = ClawTheme.type.body,
              color = ClawTheme.colors.text,
            )
            Text(
              text =
                nativeString(
                  "OpenClaw never writes health data and does not collect it in the background on its own. Turn off Health Connect in OpenClaw Settings or remove access in Health Connect at any time.",
                ),
              style = ClawTheme.type.body,
              color = ClawTheme.colors.textMuted,
            )
            ClawPrimaryButton(text = nativeString("Done"), onClick = ::finish)
          }
        }
      }
    }
  }
}
