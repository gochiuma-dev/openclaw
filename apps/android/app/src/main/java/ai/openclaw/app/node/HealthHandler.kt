package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.reflect.KClass

private const val HEALTH_RANGE_MAX_DAYS = 31L
private const val HEALTH_SLEEP_SESSION_LIMIT = 20
private const val HEALTH_READ_PAGE_LIMIT = 20

// A night that ends after midnight starts the day before; read one day back before clipping.
private val HEALTH_SLEEP_LOOKBACK: Duration = Duration.ofHours(24)

/**
 * Health Connect metrics that health.summary aggregates. Each one is gated by its own read
 * permission, so a partial grant still yields the metrics the user allowed.
 */
internal enum class HealthMetric(
  val key: String,
  val readPermission: String,
) {
  Steps("steps", "android.permission.health.READ_STEPS"),
  Sleep("sleep", "android.permission.health.READ_SLEEP"),
  HeartRate("heartRate", "android.permission.health.READ_HEART_RATE"),
  RestingHeartRate("restingHeartRate", "android.permission.health.READ_RESTING_HEART_RATE"),
  Exercise("exercise", "android.permission.health.READ_EXERCISE"),
}

/** Permission strings shared by the settings request flow and the invoke handler. */
internal object HealthConnectPermissions {
  const val READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
  const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

  val metricReads: Set<String> = HealthMetric.entries.map { it.readPermission }.toSet()

  /** Data reads plus the extended-access permissions the provider actually supports. */
  fun requestSet(
    backgroundSupported: Boolean,
    historySupported: Boolean,
  ): Set<String> =
    buildSet {
      addAll(metricReads)
      // Gateway invokes usually arrive while the app is backgrounded behind the node service.
      if (backgroundSupported) add(READ_IN_BACKGROUND)
      if (historySupported) add(READ_HISTORY)
    }
}

/** Whether this phone's Health Connect can grant reads outside the foreground and before the first grant. */
internal data class HealthExtendedAccess(
  val backgroundSupported: Boolean,
  val historySupported: Boolean,
)

internal enum class HealthConnectAvailability {
  Available,
  UpdateRequired,
  Unavailable,
}

internal data class HealthWindow(
  val start: Instant,
  val end: Instant,
)

/** One calendar day inside a requested range, with its window clipped to the request time. */
internal data class HealthDay(
  val date: LocalDate,
  val window: HealthWindow,
)

/** Parsed health.summary request: an overall window plus the calendar days it covers. */
internal data class HealthSummaryRequest(
  val period: String,
  val window: HealthWindow,
  val days: List<HealthDay>,
)

internal data class HealthAggregate(
  val stepCount: Long? = null,
  val restingHeartRateBpm: Double? = null,
  val heartRateAvgBpm: Long? = null,
  val heartRateMinBpm: Long? = null,
  val heartRateMaxBpm: Long? = null,
  val dataOrigins: Set<String> = emptySet(),
)

internal data class HealthSleepSession(
  val start: Instant,
  val end: Instant,
  /** Asleep spans from stages; the whole session when the writer recorded no stages. */
  val asleep: List<HealthWindow>,
  val dataOrigin: String?,
)

internal data class HealthExerciseSession(
  val start: Instant,
  val end: Instant,
  val dataOrigin: String?,
)

/** Health Connect seam for the provider and tests. */
internal interface HealthDataSource {
  fun availability(context: Context): HealthConnectAvailability

  fun extendedAccessSupport(context: Context): HealthExtendedAccess

  suspend fun grantedPermissions(context: Context): Set<String>

  /** Time of the newest readable record per metric, or null when the readable range has none. */
  suspend fun latestRecordTimes(
    context: Context,
    metrics: Set<HealthMetric>,
    before: Instant,
  ): Map<HealthMetric, Instant?>

  suspend fun aggregate(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthAggregate

  suspend fun sleepSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthSleepSession>

  suspend fun exerciseSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthExerciseSession>
}

internal object HealthConnectDataSource : HealthDataSource {
  override fun availability(context: Context): HealthConnectAvailability =
    when (HealthConnectClient.getSdkStatus(context)) {
      HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
      else -> HealthConnectAvailability.Unavailable
    }

  /** Feature flags decide whether background and history reads can be requested at all. */
  override fun extendedAccessSupport(context: Context): HealthExtendedAccess {
    if (availability(context) != HealthConnectAvailability.Available) return HealthExtendedAccess(false, false)
    val features = HealthConnectClient.getOrCreate(context).features
    return HealthExtendedAccess(
      backgroundSupported =
        features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
          HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
      historySupported =
        features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
          HealthConnectFeatures.FEATURE_STATUS_AVAILABLE,
    )
  }

  override suspend fun grantedPermissions(context: Context): Set<String> =
    HealthConnectClient
      .getOrCreate(context)
      .permissionController
      .getGrantedPermissions()

  override suspend fun latestRecordTimes(
    context: Context,
    metrics: Set<HealthMetric>,
    before: Instant,
  ): Map<HealthMetric, Instant?> =
    metrics.associateWith { metric ->
      when (metric) {
        HealthMetric.Steps -> newest(context, StepsRecord::class, before)?.endTime
        HealthMetric.Sleep -> newest(context, SleepSessionRecord::class, before)?.endTime
        HealthMetric.HeartRate -> newest(context, HeartRateRecord::class, before)?.endTime
        HealthMetric.RestingHeartRate -> newest(context, RestingHeartRateRecord::class, before)?.time
        HealthMetric.Exercise -> newest(context, ExerciseSessionRecord::class, before)?.endTime
      }
    }

  // Newest by start time; without READ_HEALTH_DATA_HISTORY Health Connect only returns the last 30 days.
  private suspend fun <T : Record> newest(
    context: Context,
    type: KClass<T>,
    before: Instant,
  ): T? =
    HealthConnectClient
      .getOrCreate(context)
      .readRecords(
        ReadRecordsRequest(
          recordType = type,
          timeRangeFilter = TimeRangeFilter.before(before),
          ascendingOrder = false,
          pageSize = 1,
        ),
      ).records
      .firstOrNull()

  override suspend fun aggregate(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthAggregate {
    val requested =
      buildSet<AggregateMetric<*>> {
        if (HealthMetric.Steps in metrics) add(StepsRecord.COUNT_TOTAL)
        if (HealthMetric.RestingHeartRate in metrics) add(RestingHeartRateRecord.BPM_AVG)
        if (HealthMetric.HeartRate in metrics) {
          add(HeartRateRecord.BPM_AVG)
          add(HeartRateRecord.BPM_MIN)
          add(HeartRateRecord.BPM_MAX)
        }
      }
    if (requested.isEmpty()) return HealthAggregate()
    val result =
      HealthConnectClient.getOrCreate(context).aggregate(
        AggregateRequest(metrics = requested, timeRangeFilter = TimeRangeFilter.between(window.start, window.end)),
      )
    return HealthAggregate(
      stepCount = result[StepsRecord.COUNT_TOTAL],
      restingHeartRateBpm = result[RestingHeartRateRecord.BPM_AVG]?.toDouble(),
      heartRateAvgBpm = result[HeartRateRecord.BPM_AVG],
      heartRateMinBpm = result[HeartRateRecord.BPM_MIN],
      heartRateMaxBpm = result[HeartRateRecord.BPM_MAX],
      dataOrigins = result.dataOrigins.map { it.packageName }.toSet(),
    )
  }

  override suspend fun sleepSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthSleepSession> =
    readAll(context, SleepSessionRecord::class, HealthWindow(window.start.minus(HEALTH_SLEEP_LOOKBACK), window.end))
      .map { record ->
        val stages = record.stages
        HealthSleepSession(
          start = record.startTime,
          end = record.endTime,
          asleep =
            if (stages.isEmpty()) {
              listOf(HealthWindow(record.startTime, record.endTime))
            } else {
              stages.filter { isAsleepStage(it.stage) }.map { HealthWindow(it.startTime, it.endTime) }
            },
          dataOrigin = record.metadata.dataOrigin.packageName,
        )
      }

  override suspend fun exerciseSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthExerciseSession> =
    readAll(context, ExerciseSessionRecord::class, window).map { record ->
      HealthExerciseSession(
        start = record.startTime,
        end = record.endTime,
        dataOrigin = record.metadata.dataOrigin.packageName,
      )
    }

  // Unknown and generic sleeping stages count as asleep, matching HealthKit's unspecified sleep.
  private fun isAsleepStage(stage: Int): Boolean =
    when (stage) {
      SleepSessionRecord.STAGE_TYPE_AWAKE,
      SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
      SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
      -> false

      else -> true
    }

  private suspend fun <T : Record> readAll(
    context: Context,
    type: KClass<T>,
    window: HealthWindow,
  ): List<T> {
    val client = HealthConnectClient.getOrCreate(context)
    val records = mutableListOf<T>()
    var pageToken: String? = null
    var pages = 0
    do {
      val response =
        client.readRecords(
          ReadRecordsRequest(
            recordType = type,
            timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
            pageToken = pageToken,
          ),
        )
      records += response.records
      pageToken = response.pageToken
      pages += 1
    } while (pageToken != null && pages < HEALTH_READ_PAGE_LIMIT)
    return records
  }
}

/**
 * Handles health.summary from Health Connect. The request and payload follow the iOS HealthKit
 * contract (`{"period":"today"}` → steps, sleep, resting heart rate, workouts); Android adds
 * past days, ranges, and heart-rate extremes because Health Connect can answer them on-device.
 */
class HealthHandler internal constructor(
  private val appContext: Context,
  private val dataSource: HealthDataSource = HealthConnectDataSource,
  private val clock: Clock = Clock.systemDefaultZone(),
) {
  suspend fun handleHealthSummary(paramsJson: String?): GatewaySession.InvokeResult {
    val request =
      try {
        parseHealthSummaryRequest(paramsJson, clock)
      } catch (err: IllegalArgumentException) {
        return nodeInvokeError("INVALID_REQUEST", err.message ?: "invalid health.summary params")
      }
    when (dataSource.availability(appContext)) {
      HealthConnectAvailability.Available -> {}

      HealthConnectAvailability.UpdateRequired -> {
        return nodeInvokeError("HEALTH_CONNECT_UPDATE_REQUIRED", "update Health Connect from Google Play")
      }

      HealthConnectAvailability.Unavailable -> {
        return nodeInvokeError("HEALTH_CONNECT_UNAVAILABLE", "Health Connect is not available on this device")
      }
    }
    return try {
      val granted = dataSource.grantedPermissions(appContext)
      val metrics = HealthMetric.entries.filter { it.readPermission in granted }.toSet()
      if (metrics.isEmpty()) {
        return nodeInvokeError(
          "HEALTH_PERMISSION_REQUIRED",
          "allow OpenClaw to read steps, sleep, heart rate, or exercise in Health Connect (OpenClaw Settings → Phone Capabilities → Health Connect)",
        )
      }
      GatewaySession.InvokeResult.ok(summarize(request, metrics, granted).toString())
    } catch (err: CancellationException) {
      throw err
    } catch (err: SecurityException) {
      refusedReadError(err)
    } catch (err: Throwable) {
      nodeInvokeError("HEALTH_UNAVAILABLE", err.message ?: "Health Connect query failed")
    }
  }

  /**
   * Health Connect refuses reads from a backgrounded app unless READ_HEALTH_DATA_IN_BACKGROUND is
   * granted. Name which case it is so a scheduled caller knows whether retrying can ever work.
   */
  private suspend fun refusedReadError(err: SecurityException): GatewaySession.InvokeResult {
    val reason = err.message ?: "permission denied"
    val granted = runCatching { dataSource.grantedPermissions(appContext) }.getOrDefault(emptySet())
    if (HealthConnectPermissions.READ_IN_BACKGROUND in granted) {
      return nodeInvokeError("HEALTH_PERMISSION_REQUIRED", "Health Connect refused the read ($reason)")
    }
    val access = runCatching { dataSource.extendedAccessSupport(appContext) }.getOrDefault(HealthExtendedAccess(false, false))
    return if (access.backgroundSupported) {
      nodeInvokeError(
        "HEALTH_BACKGROUND_PERMISSION_REQUIRED",
        "Health Connect refused the read ($reason); allow \"Access data in the background\" for OpenClaw in Health Connect so summaries work while OpenClaw is not on screen",
      )
    } else {
      nodeInvokeError(
        "HEALTH_FOREGROUND_ONLY",
        "Health Connect refused the read ($reason); this phone's Health Connect does not support background reads, so health.summary only works while OpenClaw is on screen",
      )
    }
  }

  private suspend fun summarize(
    request: HealthSummaryRequest,
    metrics: Set<HealthMetric>,
    granted: Set<String>,
  ): JsonObject {
    val sleepSessions =
      if (HealthMetric.Sleep in metrics) dataSource.sleepSessions(appContext, request.window) else null
    val exerciseSessions =
      if (HealthMetric.Exercise in metrics) dataSource.exerciseSessions(appContext, request.window) else null
    val total = dataSource.aggregate(appContext, request.window, metrics)
    val latest = dataSource.latestRecordTimes(appContext, metrics, clock.instant())
    val access = dataSource.extendedAccessSupport(appContext)
    val perDay =
      if (request.days.size > 1) {
        request.days.map { day -> day to dataSource.aggregate(appContext, day.window, metrics) }
      } else {
        emptyList()
      }
    val origins =
      buildSet {
        addAll(total.dataOrigins)
        sleepSessions?.forEach { session -> session.dataOrigin?.let(::add) }
        exerciseSessions?.forEach { session -> session.dataOrigin?.let(::add) }
      }
    val zone = clock.zone

    return buildJsonObject {
      put("period", request.period)
      put("startISO", request.window.start.toString())
      put("endISO", request.window.end.toString())
      put("timeZoneIdentifier", zone.id)
      putMetrics(request.window, total, sleepSessions, exerciseSessions)
      if (request.period != "today" && request.days.isNotEmpty()) {
        val firstDay = request.days.first()
        val lastDay = request.days.last()
        put("startDate", firstDay.date.toString())
        put("endDate", lastDay.date.toString())
      }
      if (sleepSessions != null) {
        val overlapping =
          sleepSessions
            .filter { it.end > request.window.start && it.start < request.window.end }
            .sortedBy { it.start }
            .takeLast(HEALTH_SLEEP_SESSION_LIMIT)
        put(
          "sleepSessions",
          buildJsonArray {
            for (session in overlapping) {
              add(
                buildJsonObject {
                  put("startISO", session.start.toString())
                  put("endISO", session.end.toString())
                  mergedDuration(session.asleep, HealthWindow(session.start, session.end))
                    ?.let { put("asleepMinutes", roundedMinutes(it)) }
                },
              )
            }
          },
        )
      }
      if (perDay.isNotEmpty()) {
        put(
          "days",
          buildJsonArray {
            for ((day, aggregate) in perDay) {
              add(
                buildJsonObject {
                  put("date", day.date.toString())
                  put("startISO", day.window.start.toString())
                  put("endISO", day.window.end.toString())
                  putMetrics(day.window, aggregate, sleepSessions, exerciseSessions)
                },
              )
            }
          },
        )
      }
      val missing = HealthMetric.entries.filter { it !in metrics }.map { it.key }
      if (missing.isNotEmpty()) put("missingPermissions", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
      // Lets a daily collector tell "no new data synced yet" apart from "nothing happened".
      put(
        "latestRecordISO",
        buildJsonObject {
          for (metric in HealthMetric.entries.filter { it in metrics }) {
            put(metric.key, latest[metric]?.toString())
          }
        },
      )
      put("backgroundAccess", HealthConnectPermissions.READ_IN_BACKGROUND in granted)
      put("backgroundSupported", access.backgroundSupported)
      put("historyAccess", HealthConnectPermissions.READ_HISTORY in granted)
      put("dataOrigins", buildJsonArray { origins.sorted().forEach { add(JsonPrimitive(it)) } })
      put("source", "healthConnect")
    }
  }

  private fun JsonObjectBuilder.putMetrics(
    window: HealthWindow,
    aggregate: HealthAggregate,
    sleepSessions: List<HealthSleepSession>?,
    exerciseSessions: List<HealthExerciseSession>?,
  ) {
    aggregate.stepCount?.let { put("stepCount", it) }
    sleepSessions
      ?.let { sessions -> mergedDuration(sessions.flatMap { it.asleep }, window) }
      ?.let { put("sleepDurationMinutes", roundedMinutes(it)) }
    aggregate.restingHeartRateBpm?.let { put("restingHeartRateBpm", (it * 10).roundToLong() / 10.0) }
    // Like HealthKit's strict start date: a workout belongs to the window it started in.
    exerciseSessions
      ?.filter { it.start >= window.start && it.start < window.end }
      ?.takeIf { it.isNotEmpty() }
      ?.let { workouts ->
        put("workoutCount", workouts.size)
        put("workoutDurationMinutes", roundedMinutes(workouts.fold(Duration.ZERO) { sum, workout -> sum + Duration.between(workout.start, workout.end) }))
      }
    aggregate.heartRateAvgBpm?.let { put("heartRateAvgBpm", it) }
    aggregate.heartRateMinBpm?.let { put("heartRateMinBpm", it) }
    aggregate.heartRateMaxBpm?.let { put("heartRateMaxBpm", it) }
  }

  /** Returns true while the Health Connect provider can be queried on this phone. */
  fun isAvailable(): Boolean = dataSource.availability(appContext) == HealthConnectAvailability.Available
}

/**
 * Parses health.summary params. `{"period":"today"}` (also the default) matches iOS; Android also
 * accepts `yesterday`, `{"period":"day","date":"YYYY-MM-DD"}`, and
 * `{"period":"range","startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD"}` (inclusive, up to 31 days).
 */
internal fun parseHealthSummaryRequest(
  paramsJson: String?,
  clock: Clock,
): HealthSummaryRequest {
  val params =
    if (paramsJson.isNullOrBlank()) {
      JsonObject(emptyMap())
    } else {
      parseJsonParamsObject(paramsJson) ?: throw IllegalArgumentException("expected JSON object")
    }
  val zone = clock.zone
  val now = clock.instant()
  val today = LocalDate.ofInstant(now, zone)
  val date = parseHealthDate(params, "date")
  val period = parseJsonString(params, "period")?.trim()?.ifEmpty { null } ?: if (date != null) "day" else "today"

  val (first, last) =
    when (period) {
      "today" -> {
        today to today
      }

      "yesterday" -> {
        today.minusDays(1) to today.minusDays(1)
      }

      "day" -> {
        val day = date ?: throw IllegalArgumentException("period \"day\" needs date (YYYY-MM-DD)")
        day to day
      }

      "range" -> {
        val start =
          parseHealthDate(params, "startDate") ?: throw IllegalArgumentException("period \"range\" needs startDate (YYYY-MM-DD)")
        val end = parseHealthDate(params, "endDate") ?: today
        if (end < start) throw IllegalArgumentException("endDate is before startDate")
        if (ChronoUnit.DAYS.between(start, end) + 1 > HEALTH_RANGE_MAX_DAYS) {
          throw IllegalArgumentException("range is limited to $HEALTH_RANGE_MAX_DAYS days")
        }
        start to end
      }

      else -> {
        throw IllegalArgumentException("unsupported period \"$period\" (use today, yesterday, day, or range)")
      }
    }
  if (last > today) throw IllegalArgumentException("date is in the future")

  val days =
    generateSequence(first) { it.plusDays(1) }
      .takeWhile { it <= last }
      .map { day -> HealthDay(day, dayWindow(day, zone, now)) }
      .toList()
  return HealthSummaryRequest(
    period = period,
    window = HealthWindow(days.first().window.start, days.last().window.end),
    days = days,
  )
}

private fun parseHealthDate(
  params: JsonObject,
  key: String,
): LocalDate? {
  val raw = parseJsonString(params, key)?.trim()?.ifEmpty { null } ?: return null
  return try {
    LocalDate.parse(raw)
  } catch (_: DateTimeParseException) {
    throw IllegalArgumentException("$key must be YYYY-MM-DD")
  }
}

private fun dayWindow(
  day: LocalDate,
  zone: ZoneId,
  now: Instant,
): HealthWindow {
  val start = day.atStartOfDay(zone).toInstant()
  val nextStart = day.plusDays(1).atStartOfDay(zone).toInstant()
  return HealthWindow(start, minOf(nextStart, now))
}

/** Clips spans to the window and merges overlaps so no minute is counted twice. */
internal fun mergedDuration(
  intervals: List<HealthWindow>,
  window: HealthWindow,
): Duration? {
  val clipped =
    intervals
      .mapNotNull { interval ->
        val start = maxOf(interval.start, window.start)
        val end = minOf(interval.end, window.end)
        if (end > start) HealthWindow(start, end) else null
      }.sortedBy { it.start }
  if (clipped.isEmpty()) return null
  var total = Duration.ZERO
  var current = clipped.first()
  for (interval in clipped.drop(1)) {
    if (interval.start <= current.end) {
      current = HealthWindow(current.start, maxOf(current.end, interval.end))
    } else {
      total += Duration.between(current.start, current.end)
      current = interval
    }
  }
  return total + Duration.between(current.start, current.end)
}

private fun roundedMinutes(duration: Duration): Int = (duration.toMillis() / 60_000.0).roundToInt()
