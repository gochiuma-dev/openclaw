package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.node.HealthConnectAvailability
import ai.openclaw.app.node.HealthConnectDataSource
import ai.openclaw.app.node.HealthConnectPermissions
import ai.openclaw.app.ui.design.ClawPanel
import ai.openclaw.app.ui.design.ClawSecondaryButton
import ai.openclaw.app.ui.design.ClawTheme
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/**
 * Phone Capabilities row for health.summary. The switch records the user's opt-in; Health
 * Connect owns the per-type grants, so the subtitle reports how many of them are allowed.
 */
@Composable
internal fun HealthConnectSettingsPanel(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val enabled by viewModel.healthSummaryEnabled.collectAsState()
  var availability by remember { mutableStateOf(HealthConnectDataSource.availability(context)) }
  var grantedReads by remember { mutableIntStateOf(0) }
  var backgroundGranted by remember { mutableStateOf(false) }
  var backgroundSupported by remember { mutableStateOf(true) }
  var requestableReads by remember { mutableStateOf(HealthConnectPermissions.metricReads(emptySet())) }
  var lastRequestDenied by remember { mutableStateOf(false) }

  suspend fun refreshGrants() {
    availability = HealthConnectDataSource.availability(context)
    if (availability != HealthConnectAvailability.Available) {
      grantedReads = 0
      backgroundGranted = false
      return
    }
    val granted = runCatching { HealthConnectDataSource.grantedPermissions(context) }.getOrDefault(emptySet())
    val access = runCatching { HealthConnectDataSource.extendedAccessSupport(context) }.getOrNull()
    requestableReads = HealthConnectPermissions.metricReads(access?.supportedFeatures.orEmpty())
    grantedReads = requestableReads.count { it in granted }
    backgroundSupported = access?.backgroundSupported ?: false
    backgroundGranted = HealthConnectPermissions.READ_IN_BACKGROUND in granted
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
      val reads = HealthConnectPermissions.metricReads.count { it in granted }
      lastRequestDenied = reads == 0
      if (reads > 0) viewModel.setHealthSummaryEnabled(true)
      scope.launch { refreshGrants() }
    }

  LaunchedEffect(Unit) { refreshGrants() }
  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) scope.launch { refreshGrants() }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  fun requestAccess() {
    val access = HealthConnectDataSource.extendedAccessSupport(context)
    permissionLauncher.launch(
      HealthConnectPermissions.requestSet(
        backgroundSupported = access.backgroundSupported,
        historySupported = access.historySupported,
        routesSupported = access.routesSupported,
        supportedFeatures = access.supportedFeatures,
      ),
    )
  }

  fun setEnabled(checked: Boolean) {
    if (!checked) {
      viewModel.setHealthSummaryEnabled(false)
      return
    }
    when (availability) {
      HealthConnectAvailability.Available -> {
        requestAccess()
      }

      HealthConnectAvailability.UpdateRequired -> {
        openHealthConnectStoreListing(context)
      }

      HealthConnectAvailability.Unavailable -> {}
    }
  }

  val subtitle =
    when {
      availability == HealthConnectAvailability.Unavailable -> {
        nativeString("Health Connect is not available on this phone.")
      }

      availability == HealthConnectAvailability.UpdateRequired -> {
        nativeString("Update Health Connect from Google Play first.")
      }

      enabled -> {
        nativeString(
          "\$grantedReads of \$total data types allowed.",
          grantedReads,
          requestableReads.size,
        )
      }

      else -> {
        nativeString("Health data stays on this phone.")
      }
    }

  SettingsTogglePanel(
    rows =
      listOf(
        SettingsToggleRow(
          title = nativeString("Health Connect"),
          subtitle = subtitle,
          icon = Icons.Default.Favorite,
          checked = enabled,
          onCheckedChange = ::setEnabled,
          enabled = availability != HealthConnectAvailability.Unavailable,
        ),
      ),
  )
  if (enabled || lastRequestDenied) {
    ClawPanel(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text =
          when {
            lastRequestDenied && !enabled -> {
              nativeString("No health data types were allowed. Choose at least one in Health Connect.")
            }

            !backgroundSupported -> {
              nativeString("This phone's Health Connect cannot read in the background, so summaries only work while OpenClaw is on screen.")
            }

            !backgroundGranted -> {
              nativeString("Allow \"Access data in the background\" so summaries work while OpenClaw is not on screen.")
            }

            else -> {
              nativeString("Summaries are computed on this phone when your Gateway asks for health.summary.")
            }
          },
        style = ClawTheme.type.caption,
        color = ClawTheme.colors.textMuted,
      )
      ClawSecondaryButton(
        text = nativeString("Choose Health Data"),
        onClick = ::requestAccess,
        enabled = availability == HealthConnectAvailability.Available,
      )
      ClawSecondaryButton(
        text = nativeString("Open Health Connect"),
        onClick = { openHealthConnectSettings(context) },
        enabled = availability == HealthConnectAvailability.Available,
      )
    }
  }
}

private fun openHealthConnectSettings(context: Context) {
  val intent = HealthConnectClient.getHealthConnectManageDataIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(intent)
  } catch (_: ActivityNotFoundException) {
    openHealthConnectStoreListing(context)
  }
}

private fun openHealthConnectStoreListing(context: Context) {
  val intent =
    Intent(Intent.ACTION_VIEW, "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri())
      .setPackage("com.android.vending")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  runCatching { context.startActivity(intent) }
}
