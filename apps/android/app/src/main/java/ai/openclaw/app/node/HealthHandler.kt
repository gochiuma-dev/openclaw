package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PlannedExerciseSessionRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.reflect.KClass

private const val HEALTH_RANGE_MAX_DAYS = 31L
private const val HEALTH_SLEEP_SESSION_LIMIT = 20
private const val HEALTH_READ_PAGE_LIMIT = 20
private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8

// Health Connect reads are binder calls; a few at once keeps a month-long range well inside the
// caller's timeout without tripping the provider's rate limits.
private const val HEALTH_READ_PARALLELISM = 6

// A night that ends after midnight starts the day before; read one day back before clipping.
private val HEALTH_SLEEP_LOOKBACK: Duration = Duration.ofHours(24)

/**
 * Health Connect types that health.summary reads. Each one is gated by its record's read
 * permission, so a partial grant still yields the types the user allowed. Reproductive-health
 * types are deliberately absent.
 */
internal enum class HealthMetric(
  val key: String,
  private val type: HealthRecordType,
  /** HealthConnectFeatures flag the provider must report available; null for core types. */
  val feature: Int? = null,
) {
  Steps("steps", record<StepsRecord> { it.endTime }),
  Sleep("sleep", record<SleepSessionRecord> { it.endTime }),
  HeartRate("heartRate", record<HeartRateRecord> { it.endTime }),
  RestingHeartRate("restingHeartRate", record<RestingHeartRateRecord> { it.time }),
  Exercise("exercise", record<ExerciseSessionRecord> { it.endTime }),
  TotalCalories("totalCalories", record<TotalCaloriesBurnedRecord> { it.endTime }),
  ActiveCalories("activeCalories", record<ActiveCaloriesBurnedRecord> { it.endTime }),
  Distance("distance", record<DistanceRecord> { it.endTime }),
  ElevationGained("elevationGained", record<ElevationGainedRecord> { it.endTime }),
  FloorsClimbed("floorsClimbed", record<FloorsClimbedRecord> { it.endTime }),
  WheelchairPushes("wheelchairPushes", record<WheelchairPushesRecord> { it.endTime }),
  Speed("speed", record<SpeedRecord> { it.endTime }),
  Power("power", record<PowerRecord> { it.endTime }),
  StepsCadence("stepsCadence", record<StepsCadenceRecord> { it.endTime }),
  CyclingPedalingCadence("cyclingPedalingCadence", record<CyclingPedalingCadenceRecord> { it.endTime }),
  Weight("weight", record<WeightRecord> { it.time }),
  Height("height", record<HeightRecord> { it.time }),
  BodyFat("bodyFat", record<BodyFatRecord> { it.time }),
  LeanBodyMass("leanBodyMass", record<LeanBodyMassRecord> { it.time }),
  BoneMass("boneMass", record<BoneMassRecord> { it.time }),
  BodyWaterMass("bodyWaterMass", record<BodyWaterMassRecord> { it.time }),
  BasalMetabolicRate("basalMetabolicRate", record<BasalMetabolicRateRecord> { it.time }),
  OxygenSaturation("oxygenSaturation", record<OxygenSaturationRecord> { it.time }),
  RespiratoryRate("respiratoryRate", record<RespiratoryRateRecord> { it.time }),
  HeartRateVariability("heartRateVariability", record<HeartRateVariabilityRmssdRecord> { it.time }),
  BloodPressure("bloodPressure", record<BloodPressureRecord> { it.time }),
  BloodGlucose("bloodGlucose", record<BloodGlucoseRecord> { it.time }),
  BodyTemperature("bodyTemperature", record<BodyTemperatureRecord> { it.time }),
  BasalBodyTemperature("basalBodyTemperature", record<BasalBodyTemperatureRecord> { it.time }),
  SkinTemperature("skinTemperature", record<SkinTemperatureRecord> { it.endTime }, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE),
  Vo2Max("vo2Max", record<Vo2MaxRecord> { it.time }),
  Nutrition("nutrition", record<NutritionRecord> { it.endTime }),
  Hydration("hydration", record<HydrationRecord> { it.endTime }),
  PlannedExercise("plannedExercise", record<PlannedExerciseSessionRecord> { it.endTime }, HealthConnectFeatures.FEATURE_PLANNED_EXERCISE),
  ;

  val recordType: KClass<out Record> get() = type.kClass

  /** When a record of this type finished, for latestRecordISO. */
  fun endTime(record: Record): Instant = type.endTime(record)

  /** Some types share a permission (steps cadence uses READ_STEPS, pedaling cadence READ_EXERCISE). */
  val readPermission: String get() = HealthPermission.getReadPermission(recordType)

  fun isSupported(access: HealthExtendedAccess): Boolean = feature == null || feature in access.supportedFeatures
}

/** A record class plus its end time; Health Connect keeps the shared interval/instant interfaces internal. */
internal class HealthRecordType(
  val kClass: KClass<out Record>,
  val endTime: (Record) -> Instant,
)

private inline fun <reified T : Record> record(crossinline endTime: (T) -> Instant) = HealthRecordType(T::class) { endTime(it as T) }

/** Permission strings shared by the settings request flow and the invoke handler. */
internal object HealthConnectPermissions {
  const val READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
  const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

  /** Lets health.summary read routes other apps wrote without a per-session consent dialog. */
  const val READ_EXERCISE_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"

  val metricReads: Set<String> = HealthMetric.entries.map { it.readPermission }.toSet()

  /** Data reads this provider can grant; feature-gated types drop out where the feature is missing. */
  fun metricReads(supportedFeatures: Set<Int>): Set<String> =
    HealthMetric.entries
      .filter { it.feature == null || it.feature in supportedFeatures }
      .map { it.readPermission }
      .toSet()

  /** Data reads plus the extended-access permissions the provider actually supports. */
  fun requestSet(
    backgroundSupported: Boolean,
    historySupported: Boolean,
    routesSupported: Boolean = false,
    supportedFeatures: Set<Int> = emptySet(),
  ): Set<String> =
    buildSet {
      addAll(metricReads(supportedFeatures))
      // Gateway invokes usually arrive while the app is backgrounded behind the node service.
      if (backgroundSupported) add(READ_IN_BACKGROUND)
      if (historySupported) add(READ_HISTORY)
      if (routesSupported) add(READ_EXERCISE_ROUTES)
    }
}

/** Whether this phone's Health Connect can grant reads outside the foreground and before the first grant. */
internal data class HealthExtendedAccess(
  val backgroundSupported: Boolean,
  val historySupported: Boolean,
  /** READ_EXERCISE_ROUTES exists from Android 15's platform Health Connect. */
  val routesSupported: Boolean = false,
  /** Available HealthConnectFeatures flags among those [HealthMetric.feature] needs. */
  val supportedFeatures: Set<Int> = emptySet(),
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
  /** Route points are opt-in; a month of GPS fixes would dwarf the rest of the payload. */
  val includeRoutes: Boolean = false,
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
  /** Readable exercise type from [healthExerciseTypeName]. */
  val type: String = healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT),
  val title: String? = null,
  val route: HealthRoute = HealthRoute(HealthRouteStatus.None),
)

/** Mirrors ExerciseRouteResult: Data, ConsentRequired, NoData. */
internal enum class HealthRouteStatus(
  val key: String,
) {
  Data("data"),
  ConsentRequired("consent_required"),
  None("none"),
}

internal data class HealthRoutePoint(
  val time: Instant,
  val latitude: Double,
  val longitude: Double,
  val altitudeMeters: Double? = null,
  val horizontalAccuracyMeters: Double? = null,
)

internal data class HealthRoute(
  val status: HealthRouteStatus,
  val points: List<HealthRoutePoint> = emptyList(),
)

internal data class HealthPlannedExercise(
  val start: Instant,
  val end: Instant,
  val type: String,
  val title: String? = null,
  val completed: Boolean = false,
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

  /** One aggregate call for the [HealthReading.Totals] types in [metrics]. */
  suspend fun totals(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthTotals

  /** Every measurement of one [HealthReading.Latest] or [HealthReading.Stats] type in the window. */
  suspend fun samples(
    context: Context,
    window: HealthWindow,
    metric: HealthMetric,
  ): List<HealthSample>

  suspend fun plannedExercises(
    context: Context,
    window: HealthWindow,
  ): List<HealthPlannedExercise>

  /** Every raw record of one health.samples type overlapping [window], read page by page. */
  suspend fun detailRecords(
    context: Context,
    window: HealthWindow,
    type: HealthSampleType,
  ): HealthDetailPage = HealthDetailPage(emptyList())
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
      routesSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
      supportedFeatures =
        HealthMetric.entries
          .mapNotNull { it.feature }
          .filter { features.getFeatureStatus(it) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE }
          .toSet(),
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
  ): Map<HealthMetric, Instant?> = metrics.associateWith { metric -> newest(context, metric.anyRecordType, before)?.let(metric::endTime) }

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
        type = healthExerciseTypeName(record.exerciseType),
        title = record.title?.trim()?.ifEmpty { null },
        route = healthRoute(record.exerciseRouteResult),
      )
    }

  override suspend fun totals(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthTotals {
    val fields = metrics.flatMap { (HEALTH_READINGS[it] as? HealthReading.Totals)?.fields.orEmpty() }
    if (fields.isEmpty()) return HealthTotals()
    val result =
      HealthConnectClient.getOrCreate(context).aggregate(
        AggregateRequest(metrics = fields.map { it.metric }.toSet(), timeRangeFilter = TimeRangeFilter.between(window.start, window.end)),
      )
    return HealthTotals(
      values = fields.mapNotNull { field -> field.read(result)?.let { field.key to it } }.toMap(),
      dataOrigins = result.dataOrigins.map { it.packageName }.toSet(),
    )
  }

  override suspend fun samples(
    context: Context,
    window: HealthWindow,
    metric: HealthMetric,
  ): List<HealthSample> {
    val sample =
      when (val reading = HEALTH_READINGS[metric]) {
        is HealthReading.Latest -> reading.sample
        is HealthReading.Stats -> reading.sample
        else -> return emptyList()
      }
    return readAll(context, metric.anyRecordType, window).map(sample)
  }

  override suspend fun plannedExercises(
    context: Context,
    window: HealthWindow,
  ): List<HealthPlannedExercise> =
    readAll(context, PlannedExerciseSessionRecord::class, window).map { record ->
      HealthPlannedExercise(
        start = record.startTime,
        end = record.endTime,
        type = healthExerciseTypeName(record.exerciseType),
        title = record.title?.trim()?.ifEmpty { null },
        completed = record.completedExerciseSessionId != null,
      )
    }

  override suspend fun detailRecords(
    context: Context,
    window: HealthWindow,
    type: HealthSampleType,
  ): HealthDetailPage = readHealthDetails(HealthConnectClient.getOrCreate(context), window, type)

  @Suppress("UNCHECKED_CAST")
  private val HealthMetric.anyRecordType: KClass<Record> get() = recordType as KClass<Record>

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
    unavailableError()?.let { return it }
    return try {
      val granted = dataSource.grantedPermissions(appContext)
      val access = dataSource.extendedAccessSupport(appContext)
      val metrics = HealthMetric.entries.filter { it.readPermission in granted && it.isSupported(access) }.toSet()
      if (metrics.isEmpty()) {
        return nodeInvokeError(
          "HEALTH_PERMISSION_REQUIRED",
          "allow OpenClaw to read steps, sleep, heart rate, or exercise in Health Connect (OpenClaw Settings → Phone Capabilities → Health Connect)",
        )
      }
      GatewaySession.InvokeResult.ok(summarize(request, metrics, granted, access).toString())
    } catch (err: CancellationException) {
      throw err
    } catch (err: SecurityException) {
      refusedReadError(err)
    } catch (err: Throwable) {
      nodeInvokeError("HEALTH_UNAVAILABLE", err.message ?: "Health Connect query failed")
    }
  }

  /**
   * Handles health.samples: the raw per-sample records of one day (heart rate, sleep stages, HRV,
   * SpO2, respiratory rate, skin temperature, steps) under the same opt-in and Health Connect
   * grants as health.summary. Types without a grant are left out and named in missingPermissions.
   */
  suspend fun handleHealthSamples(paramsJson: String?): GatewaySession.InvokeResult {
    val request =
      try {
        parseHealthSamplesRequest(paramsJson, clock)
      } catch (err: IllegalArgumentException) {
        return nodeInvokeError("INVALID_REQUEST", err.message ?: "invalid health.samples params")
      }
    unavailableError()?.let { return it }
    return try {
      val granted = dataSource.grantedPermissions(appContext)
      val access = dataSource.extendedAccessSupport(appContext)
      if (HealthMetric.entries.none { it.readPermission in granted && it.isSupported(access) }) {
        return nodeInvokeError(
          "HEALTH_PERMISSION_REQUIRED",
          "allow OpenClaw to read health data in Health Connect (OpenClaw Settings → Phone Capabilities → Health Connect)",
        )
      }
      val payload = collectHealthSamples(appContext, dataSource, request, granted, access, clock.zone).toString()
      val bytes = payload.toByteArray(Charsets.UTF_8).size
      if (bytes > HEALTH_SAMPLES_MAX_PAYLOAD_BYTES) {
        nodeInvokeError(
          "HEALTH_SAMPLES_TOO_LARGE",
          "payload is $bytes bytes (limit $HEALTH_SAMPLES_MAX_PAYLOAD_BYTES); request fewer types or a shorter startISO/endISO range",
        )
      } else {
        GatewaySession.InvokeResult.ok(payload)
      }
    } catch (err: CancellationException) {
      throw err
    } catch (err: SecurityException) {
      refusedReadError(err)
    } catch (err: Throwable) {
      nodeInvokeError("HEALTH_UNAVAILABLE", err.message ?: "Health Connect query failed")
    }
  }

  private fun unavailableError(): GatewaySession.InvokeResult? =
    when (dataSource.availability(appContext)) {
      HealthConnectAvailability.Available -> null
      HealthConnectAvailability.UpdateRequired -> nodeInvokeError("HEALTH_CONNECT_UPDATE_REQUIRED", "update Health Connect from Google Play")
      HealthConnectAvailability.Unavailable -> nodeInvokeError("HEALTH_CONNECT_UNAVAILABLE", "Health Connect is not available on this device")
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

  /** A read that succeeded, so a null value still means "nothing there" rather than "failed". */
  private class Read<T>(
    val value: T,
  )

  /**
   * Runs the reads in parallel. The original five types fail the whole summary as before (their
   * SecurityException names the background-access problem); every other type fails on its own and
   * is named in `readErrors`.
   */
  private class Reads(
    private val scope: CoroutineScope,
  ) {
    private val gate = Semaphore(HEALTH_READ_PARALLELISM)
    val errors = ConcurrentHashMap<String, String>()

    fun <T> required(block: suspend () -> T): Deferred<T> = scope.async { gate.withPermit { block() } }

    fun <T> isolated(
      key: String,
      block: suspend () -> T,
    ): Deferred<Read<T>?> =
      scope.async {
        try {
          Read(gate.withPermit { block() })
        } catch (err: CancellationException) {
          throw err
        } catch (err: Exception) {
          errors[key] = err.message ?: err::class.java.simpleName
          null
        }
      }

    /** One aggregate call for all [metrics]; if Health Connect rejects it, one call per type. */
    fun totals(
      metrics: Set<HealthMetric>,
      read: suspend (Set<HealthMetric>) -> HealthTotals,
    ): Deferred<HealthTotals> =
      scope.async {
        if (metrics.isEmpty()) return@async HealthTotals()
        try {
          gate.withPermit { read(metrics) }
        } catch (err: CancellationException) {
          throw err
        } catch (err: Exception) {
          if (metrics.size == 1) {
            errors[metrics.single().key] = err.message ?: err::class.java.simpleName
            return@async HealthTotals()
          }
          val parts = metrics.map { metric -> isolated(metric.key) { read(setOf(metric)) } }.awaitAll().mapNotNull { it?.value }
          HealthTotals(parts.flatMap { it.values.entries }.associate { it.key to it.value }, parts.flatMap { it.dataOrigins }.toSet())
        }
      }
  }

  /** The table-driven reads for one window. */
  private class WindowExtras(
    val metrics: Set<HealthMetric>,
    val totals: HealthTotals,
    val samples: Map<HealthMetric, List<HealthSample>>,
    val planned: List<HealthPlannedExercise>?,
    val workoutTotals: Map<HealthExerciseSession, HealthTotals>,
  )

  private suspend fun summarize(
    request: HealthSummaryRequest,
    metrics: Set<HealthMetric>,
    granted: Set<String>,
    access: HealthExtendedAccess,
  ): JsonObject =
    coroutineScope {
      val reads = Reads(this)
      val sleepRead =
        if (HealthMetric.Sleep in metrics) reads.required { dataSource.sleepSessions(appContext, request.window) } else null
      val exerciseRead =
        if (HealthMetric.Exercise in metrics) reads.required { dataSource.exerciseSessions(appContext, request.window) } else null
      val totalRead = reads.required { dataSource.aggregate(appContext, request.window, metrics) }
      val perDayReads =
        if (request.days.size > 1) {
          request.days.map { day -> day to reads.required { dataSource.aggregate(appContext, day.window, metrics) } }
        } else {
          emptyList()
        }
      val now = clock.instant()
      val latestReads = metrics.map { metric -> metric to reads.isolated(metric.key) { dataSource.latestRecordTimes(appContext, setOf(metric), now)[metric] } }
      val totalsMetrics = metrics.filter { HEALTH_READINGS[it] is HealthReading.Totals }.toSet()
      val readTotals: suspend (HealthWindow, Set<HealthMetric>) -> HealthTotals = { window, types -> dataSource.totals(appContext, window, types) }
      val windowTotalsRead = reads.totals(totalsMetrics) { readTotals(request.window, it) }
      val dayTotalsReads = perDayReads.map { (day, _) -> day to reads.totals(totalsMetrics) { readTotals(day.window, it) } }
      val sampleReads =
        metrics
          .filter { HEALTH_READINGS[it] is HealthReading.Latest || HEALTH_READINGS[it] is HealthReading.Stats }
          .map { metric -> metric to reads.isolated(metric.key) { dataSource.samples(appContext, request.window, metric) } }
      val plannedRead =
        if (HealthMetric.PlannedExercise in metrics) {
          reads.isolated(HealthMetric.PlannedExercise.key) { dataSource.plannedExercises(appContext, request.window) }
        } else {
          null
        }

      val sleepSessions = sleepRead?.await()
      val exerciseSessions = exerciseRead?.await()
      val workoutMetrics = HEALTH_WORKOUT_TOTAL_METRICS.filter { it in metrics }.toSet()
      val workoutTotals =
        exerciseSessions
          .orEmpty()
          .filter { workoutMetrics.isNotEmpty() && it.start >= request.window.start && it.start < request.window.end }
          .map { workout -> workout to reads.totals(workoutMetrics) { readTotals(HealthWindow(workout.start, workout.end), it) } }
          .associate { (workout, read) -> workout to read.await() }
      val total = totalRead.await()
      val perDay = perDayReads.map { (day, read) -> day to read.await() }
      val latest = latestReads.mapNotNull { (metric, read) -> read.await()?.let { metric to it.value } }.toMap()
      val samples = sampleReads.mapNotNull { (metric, read) -> read.await()?.let { metric to it.value } }.toMap()
      val planned = plannedRead?.await()?.value
      val windowTotals = windowTotalsRead.await()
      val dayTotals = dayTotalsReads.associate { (day, read) -> day to read.await() }

      fun extras(totals: HealthTotals) = WindowExtras(metrics, totals, samples, planned, workoutTotals)

      val origins =
        buildSet {
          addAll(total.dataOrigins)
          addAll(windowTotals.dataOrigins)
          sleepSessions?.forEach { session -> session.dataOrigin?.let(::add) }
          exerciseSessions?.forEach { session -> session.dataOrigin?.let(::add) }
          samples.values.flatten().forEach { sample -> sample.dataOrigin?.let(::add) }
        }
      val zone = clock.zone
      val routes =
        when {
          HealthConnectPermissions.READ_EXERCISE_ROUTES !in granted -> RouteOutput.Hidden
          request.includeRoutes -> RouteOutput.WithPoints
          else -> RouteOutput.Summary
        }

      buildJsonObject {
        put("period", request.period)
        put("startISO", request.window.start.toString())
        put("endISO", request.window.end.toString())
        put("timeZoneIdentifier", zone.id)
        putMetrics(request.window, total, sleepSessions, exerciseSessions, routes, extras(windowTotals))
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
                    putMetrics(day.window, aggregate, sleepSessions, exerciseSessions, routes, extras(dayTotals[day] ?: HealthTotals()))
                  },
                )
              }
            },
          )
        }
        val missing = HealthMetric.entries.filter { it.isSupported(access) && it !in metrics }.map { it.key }
        if (missing.isNotEmpty()) put("missingPermissions", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
        // Lets a daily collector tell "no new data synced yet" apart from "nothing happened".
        put(
          "latestRecordISO",
          buildJsonObject {
            // A type whose read failed is left out here and named in readErrors.
            for (metric in HealthMetric.entries.filter { it in metrics && it in latest }) {
              put(metric.key, latest[metric]?.toString())
            }
          },
        )
        put("backgroundAccess", HealthConnectPermissions.READ_IN_BACKGROUND in granted)
        put("backgroundSupported", access.backgroundSupported)
        put("historyAccess", HealthConnectPermissions.READ_HISTORY in granted)
        put("dataOrigins", buildJsonArray { origins.sorted().forEach { add(JsonPrimitive(it)) } })
        if (reads.errors.isNotEmpty()) {
          put("readErrors", buildJsonObject { reads.errors.toSortedMap().forEach { (key, message) -> put(key, message) } })
        }
        put("source", "healthConnect")
      }
    }

  private fun JsonObjectBuilder.putMetrics(
    window: HealthWindow,
    aggregate: HealthAggregate,
    sleepSessions: List<HealthSleepSession>?,
    exerciseSessions: List<HealthExerciseSession>?,
    routes: RouteOutput,
    extras: WindowExtras,
  ) {
    aggregate.stepCount?.let { put("stepCount", it) }
    sleepSessions
      ?.let { sessions -> mergedDuration(sessions.flatMap { it.asleep }, window) }
      ?.let { put("sleepDurationMinutes", roundedMinutes(it)) }
    aggregate.restingHeartRateBpm?.let { put("restingHeartRateBpm", (it * 10).roundToLong() / 10.0) }
    // Like HealthKit's strict start date: a workout belongs to the window it started in.
    val workouts =
      exerciseSessions
        ?.filter { it.start >= window.start && it.start < window.end }
        ?.sortedBy { it.start }
    workouts
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        put("workoutCount", it.size)
        put("workoutDurationMinutes", roundedMinutes(it.fold(Duration.ZERO) { sum, workout -> sum + Duration.between(workout.start, workout.end) }))
      }
    aggregate.heartRateAvgBpm?.let { put("heartRateAvgBpm", it) }
    aggregate.heartRateMinBpm?.let { put("heartRateMinBpm", it) }
    aggregate.heartRateMaxBpm?.let { put("heartRateMaxBpm", it) }
    putReadings(window, extras.metrics, extras.totals, extras.samples)
    extras.planned?.let { planned ->
      put(
        "plannedWorkouts",
        buildJsonArray {
          for (plan in planned.filter { it.start >= window.start && it.start < window.end }.sortedBy { it.start }) {
            add(
              buildJsonObject {
                put("type", plan.type)
                plan.title?.let { put("title", it) }
                put("startISO", plan.start.toString())
                put("endISO", plan.end.toString())
                put("completed", plan.completed)
              },
            )
          }
        },
      )
    }
    // Present whenever exercise is readable, so an empty list means "no workouts" rather than "no access".
    if (workouts != null) {
      put(
        "workouts",
        buildJsonArray {
          for (workout in workouts) {
            add(
              buildJsonObject {
                put("type", workout.type)
                workout.title?.let { put("title", it) }
                put("startISO", workout.start.toString())
                put("endISO", workout.end.toString())
                put("durationMinutes", roundedMinutes(Duration.between(workout.start, workout.end)))
                workout.dataOrigin?.let { put("dataOrigin", it) }
                extras.workoutTotals[workout]?.values?.forEach { (key, value) -> putReadingValue(key, value) }
                putRoute(workout.route, routes)
              },
            )
          }
        },
      )
    }
  }

  // Without READ_EXERCISE_ROUTES every foreign route reads as ConsentRequired, so stay silent
  // rather than report a status that only reflects the missing grant.
  private fun JsonObjectBuilder.putRoute(
    route: HealthRoute,
    output: RouteOutput,
  ) {
    if (output == RouteOutput.Hidden) return
    put("routeStatus", route.status.key)
    if (route.status != HealthRouteStatus.Data) return
    put("routePointCount", route.points.size)
    put("routeDistanceMeters", routeDistanceMeters(route.points).roundToLong())
    if (output != RouteOutput.WithPoints) return
    put(
      "route",
      buildJsonArray {
        for (point in route.points) {
          add(
            buildJsonObject {
              put("t", point.time.toString())
              put("lat", point.latitude)
              put("lon", point.longitude)
              point.altitudeMeters?.let { put("alt", it) }
              point.horizontalAccuracyMeters?.let { put("acc", it) }
            },
          )
        }
      },
    )
  }

  private enum class RouteOutput { Hidden, Summary, WithPoints }

  /** Returns true while the Health Connect provider can be queried on this phone. */
  fun isAvailable(): Boolean = dataSource.availability(appContext) == HealthConnectAvailability.Available
}

/**
 * Parses health.summary params. `{"period":"today"}` (also the default) matches iOS; Android also
 * accepts `yesterday`, `{"period":"day","date":"YYYY-MM-DD"}`, and
 * `{"period":"range","startDate":"YYYY-MM-DD","endDate":"YYYY-MM-DD"}` (inclusive, up to 31 days).
 * `"includeRoutes":true` adds each workout's GPS points.
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
  // LocalDate.ofInstant is API 34; minSdk is 31.
  val today = now.atZone(zone).toLocalDate()
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
    includeRoutes = parseJsonBooleanFlag(params, "includeRoutes") ?: false,
  )
}

internal fun parseHealthDate(
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

internal fun dayWindow(
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

/** Health Connect exercise types by constant name, so callers read "RUNNING" instead of 56. */
private val HEALTH_EXERCISE_TYPE_NAMES: Map<Int, String> =
  mapOf(
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "OTHER_WORKOUT",
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "BADMINTON",
    ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "BASEBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "BASKETBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "BIKING",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "BIKING_STATIONARY",
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "BOOT_CAMP",
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "BOXING",
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "CALISTHENICS",
    ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "CRICKET",
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "DANCING",
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "ELLIPTICAL",
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "EXERCISE_CLASS",
    ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "FENCING",
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "FOOTBALL_AMERICAN",
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "FOOTBALL_AUSTRALIAN",
    ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "FRISBEE_DISC",
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "GOLF",
    ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "GUIDED_BREATHING",
    ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "GYMNASTICS",
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "HANDBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIGH_INTENSITY_INTERVAL_TRAINING",
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "HIKING",
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "ICE_HOCKEY",
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "ICE_SKATING",
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "MARTIAL_ARTS",
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "PADDLING",
    ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "PARAGLIDING",
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "PILATES",
    ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "RACQUETBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "ROCK_CLIMBING",
    ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "ROLLER_HOCKEY",
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "ROWING",
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "ROWING_MACHINE",
    ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "RUGBY",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "RUNNING",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "RUNNING_TREADMILL",
    ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "SAILING",
    ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "SCUBA_DIVING",
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "SKATING",
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "SKIING",
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "SNOWBOARDING",
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "SNOWSHOEING",
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "SOCCER",
    ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "SOFTBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "SQUASH",
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "STAIR_CLIMBING",
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "STAIR_CLIMBING_MACHINE",
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "STRENGTH_TRAINING",
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "STRETCHING",
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "SURFING",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "SWIMMING_OPEN_WATER",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "SWIMMING_POOL",
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "TABLE_TENNIS",
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "TENNIS",
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "VOLLEYBALL",
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "WALKING",
    ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "WATER_POLO",
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "WEIGHTLIFTING",
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "WHEELCHAIR",
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "YOGA",
  )

/** Readable name for an ExerciseSessionRecord exercise type; types newer than this table become `OTHER_<n>`. */
internal fun healthExerciseTypeName(type: Int): String = HEALTH_EXERCISE_TYPE_NAMES[type] ?: "OTHER_$type"

// Other apps' routes come back as ConsentRequired without READ_EXERCISE_ROUTES, and always
// while OpenClaw is in the background.
internal fun healthRoute(result: ExerciseRouteResult): HealthRoute =
  when (result) {
    is ExerciseRouteResult.Data -> {
      HealthRoute(
        HealthRouteStatus.Data,
        result.exerciseRoute.route.map { location ->
          HealthRoutePoint(
            time = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude?.inMeters,
            horizontalAccuracyMeters = location.horizontalAccuracy?.inMeters,
          )
        },
      )
    }

    is ExerciseRouteResult.ConsentRequired -> {
      HealthRoute(HealthRouteStatus.ConsentRequired)
    }

    else -> {
      HealthRoute(HealthRouteStatus.None)
    }
  }

/** Great-circle length of the route in order, in meters (haversine on the mean Earth radius). */
internal fun routeDistanceMeters(points: List<HealthRoutePoint>): Double =
  points.zipWithNext().sumOf { (a, b) ->
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    2 * EARTH_MEAN_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
  }

private fun roundedMinutes(duration: Duration): Int = (duration.toMillis() / 60_000.0).roundToInt()
