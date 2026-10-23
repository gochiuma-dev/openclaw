package ai.openclaw.app.node

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/** health.samples covers at most one day per call; a day of 1 Hz heart rate stays near 1.5 MB. */
private val HEALTH_SAMPLES_RANGE_MAX: Duration = Duration.ofHours(24)

// Health Connect caps a page at 5000 records; 40 pages bounds a runaway writer.
private const val HEALTH_SAMPLES_PAGE_SIZE = 5000
private const val HEALTH_SAMPLES_PAGE_LIMIT = 40
private const val HEALTH_SAMPLES_PARALLELISM = 4

/**
 * Well under OkHttp's 16 MiB websocket send queue and the gateway's 25 MiB frame; larger days
 * should be fetched per type.
 */
internal const val HEALTH_SAMPLES_MAX_PAYLOAD_BYTES = 6 * 1024 * 1024

// Interval records that straddle the window edge are read with slack, then kept by their own time.
private val HEALTH_SAMPLES_INTERVAL_SLACK: Duration = Duration.ofHours(3)

// A night that ends in the window started the evening before.
private val HEALTH_SAMPLES_SLEEP_LOOKBACK: Duration = Duration.ofHours(24)

/**
 * Types health.samples returns. Compact types are arrays of rows whose columns are named in the
 * payload's `fields`; `o` indexes the payload's `origins`.
 */
internal enum class HealthSampleType(
  val key: String,
  val metric: HealthMetric,
  /** Row columns for compact types; null for types returned as objects. */
  val fields: List<String>?,
) {
  HeartRate("heartRate", HealthMetric.HeartRate, listOf("t", "bpm", "o")),
  Sleep("sleep", HealthMetric.Sleep, null),
  Hrv("hrv", HealthMetric.HeartRateVariability, listOf("t", "rmssdMs", "o")),
  Spo2("spo2", HealthMetric.OxygenSaturation, listOf("t", "percent", "o")),
  RespiratoryRate("respiratoryRate", HealthMetric.RespiratoryRate, listOf("t", "perMinute", "o")),
  SkinTemperature("skinTemperature", HealthMetric.SkinTemperature, null),
  Steps("steps", HealthMetric.Steps, listOf("start", "end", "count", "o")),
  ;

  companion object {
    fun fromKey(key: String): HealthSampleType? = entries.firstOrNull { it.key == key }
  }
}

internal sealed interface HealthDetail {
  val origin: String?
}

/** One timed value: a heart-rate sample, HRV, SpO2, or respiratory rate. */
internal data class HealthPointDetail(
  val time: Instant,
  val value: Double,
  override val origin: String?,
) : HealthDetail

internal data class HealthStepsDetail(
  val start: Instant,
  val end: Instant,
  val count: Long,
  override val origin: String?,
) : HealthDetail

internal data class HealthSleepStageDetail(
  val start: Instant,
  val end: Instant,
  val stage: String,
)

internal data class HealthSleepDetail(
  val start: Instant,
  val end: Instant,
  override val origin: String?,
  val title: String? = null,
  val stages: List<HealthSleepStageDetail> = emptyList(),
) : HealthDetail

internal data class HealthSkinTemperatureDetail(
  val start: Instant,
  val end: Instant,
  override val origin: String?,
  val baselineCelsius: Double? = null,
  val location: String? = null,
  val deltas: List<Pair<Instant, Double>> = emptyList(),
) : HealthDetail

/** Records from a paged read; [truncated] when the page limit stopped it early. */
internal data class HealthDetailPage(
  val records: List<HealthDetail>,
  val truncated: Boolean = false,
)

internal data class HealthSamplesRequest(
  /** The calendar day asked for, or null for an explicit startISO/endISO range. */
  val date: LocalDate?,
  val window: HealthWindow,
  val types: List<HealthSampleType>,
)

/**
 * Parses health.samples params: `{"date":"YYYY-MM-DD"}` (the phone's calendar day), or
 * `{"startISO":…,"endISO":…}` spanning at most 24 hours, or nothing for today so far.
 * `types` (array or comma-separated string of [HealthSampleType] keys) narrows the read.
 */
internal fun parseHealthSamplesRequest(
  paramsJson: String?,
  clock: Clock,
): HealthSamplesRequest {
  val params =
    if (paramsJson.isNullOrBlank()) {
      JsonObject(emptyMap())
    } else {
      parseJsonParamsObject(paramsJson) ?: throw IllegalArgumentException("expected JSON object")
    }
  val zone = clock.zone
  val now = clock.instant()
  val today = now.atZone(zone).toLocalDate()
  val date = parseHealthDate(params, "date")
  val startRaw = parseJsonString(params, "startISO")?.trim()?.ifEmpty { null }
  val endRaw = parseJsonString(params, "endISO")?.trim()?.ifEmpty { null }
  val window =
    when {
      date != null && (startRaw != null || endRaw != null) -> {
        throw IllegalArgumentException("use either date or startISO/endISO, not both")
      }

      date != null -> {
        if (date > today) throw IllegalArgumentException("date is in the future")
        dayWindow(date, zone, now)
      }

      startRaw != null -> {
        val start = parseHealthInstant(startRaw, "startISO")
        val end = endRaw?.let { parseHealthInstant(it, "endISO") } ?: now
        if (end <= start) throw IllegalArgumentException("endISO must be after startISO")
        if (Duration.between(start, end) > HEALTH_SAMPLES_RANGE_MAX) {
          throw IllegalArgumentException("startISO/endISO is limited to 24 hours")
        }
        if (start >= now) throw IllegalArgumentException("startISO is in the future")
        HealthWindow(start, minOf(end, now))
      }

      endRaw != null -> {
        throw IllegalArgumentException("endISO needs startISO")
      }

      else -> {
        dayWindow(today, zone, now)
      }
    }
  return HealthSamplesRequest(date ?: if (startRaw == null) today else null, window, parseHealthSampleTypes(params))
}

private fun parseHealthInstant(
  raw: String,
  key: String,
): Instant =
  try {
    OffsetDateTime.parse(raw).toInstant()
  } catch (_: DateTimeParseException) {
    throw IllegalArgumentException("$key must be an ISO-8601 time with offset (e.g. 2026-10-22T00:00:00+09:00)")
  }

private fun parseHealthSampleTypes(params: JsonObject): List<HealthSampleType> {
  val keys =
    when (val raw = params["types"]) {
      null -> return HealthSampleType.entries
      is JsonArray -> raw.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("types must be strings") }
      is JsonPrimitive -> raw.content.split(',')
      else -> throw IllegalArgumentException("types must be an array of type names")
    }.map { it.trim() }.filter { it.isNotEmpty() }
  if (keys.isEmpty()) return HealthSampleType.entries
  val requested =
    keys.map { key ->
      HealthSampleType.fromKey(key)
        ?: throw IllegalArgumentException("unknown type \"$key\" (use ${HealthSampleType.entries.joinToString { it.key }})")
    }
  // Payload order stays fixed whatever order the caller listed.
  return HealthSampleType.entries.filter { it in requested }
}

/** Reads the requested types in parallel and builds the health.samples payload. */
internal suspend fun collectHealthSamples(
  context: Context,
  dataSource: HealthDataSource,
  request: HealthSamplesRequest,
  granted: Set<String>,
  access: HealthExtendedAccess,
  zone: ZoneId,
): JsonObject =
  coroutineScope {
    val window = request.window
    val unsupported = request.types.filter { !it.metric.isSupported(access) }
    val missing = request.types.filter { it.metric.isSupported(access) && it.metric.readPermission !in granted }
    val readable = request.types.filter { it !in unsupported && it !in missing }
    val gate = Semaphore(HEALTH_SAMPLES_PARALLELISM)
    val errors = ConcurrentHashMap<String, String>()
    val reads =
      readable.map { type ->
        type to
          async {
            try {
              gate.withPermit { dataSource.detailRecords(context, readWindow(type, window), type) }
            } catch (err: CancellationException) {
              throw err
            } catch (err: SecurityException) {
              // A background refusal applies to every type; let the handler name it.
              throw err
            } catch (err: Exception) {
              errors[type.key] = err.message ?: err::class.java.simpleName
              null
            }
          }
      }
    val results =
      reads
        .mapNotNull { (type, read) -> read.await()?.let { type to HealthDetailPage(keepInWindow(type, it.records, window), it.truncated) } }
        .toMap()
    val origins =
      results.values
        .flatMap { page -> page.records.mapNotNull { it.origin } }
        .toSortedSet()
        .toList()
    val originIndex = origins.withIndex().associate { (index, origin) -> origin to index }

    fun JsonArrayBuilder.addOrigin(origin: String?) {
      add(origin?.let { originIndex[it] }?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    buildJsonObject {
      request.date?.let { put("date", it.toString()) }
      put("startISO", window.start.toString())
      put("endISO", window.end.toString())
      put("timeZoneIdentifier", zone.id)
      put("origins", buildJsonArray { origins.forEach { add(JsonPrimitive(it)) } })
      put(
        "fields",
        buildJsonObject {
          for (type in results.keys) {
            type.fields?.let { fields -> put(type.key, buildJsonArray { fields.forEach { add(JsonPrimitive(it)) } }) }
          }
          if (HealthSampleType.Sleep in results) {
            put("sleep.stages", buildJsonArray { listOf("start", "end", "stage").forEach { add(JsonPrimitive(it)) } })
          }
          if (HealthSampleType.SkinTemperature in results) {
            put("skinTemperature.deltas", buildJsonArray { listOf("t", "deltaC").forEach { add(JsonPrimitive(it)) } })
          }
        },
      )
      for ((type, page) in results) {
        put(
          type.key,
          buildJsonArray {
            for (record in page.records) {
              when (record) {
                is HealthPointDetail -> {
                  add(
                    buildJsonArray {
                      add(JsonPrimitive(record.time.epochSecond))
                      if (type == HealthSampleType.HeartRate) add(JsonPrimitive(record.value.roundToLong())) else add(JsonPrimitive(rounded(record.value, 1)))
                      addOrigin(record.origin)
                    },
                  )
                }

                is HealthStepsDetail -> {
                  add(
                    buildJsonArray {
                      add(JsonPrimitive(record.start.epochSecond))
                      add(JsonPrimitive(record.end.epochSecond))
                      add(JsonPrimitive(record.count))
                      addOrigin(record.origin)
                    },
                  )
                }

                is HealthSleepDetail -> {
                  add(
                    buildJsonObject {
                      put("start", record.start.epochSecond)
                      put("end", record.end.epochSecond)
                      put("startISO", record.start.toString())
                      put("endISO", record.end.toString())
                      record.origin?.let { originIndex[it] }?.let { put("o", it) }
                      record.title?.let { put("title", it) }
                      put(
                        "stages",
                        buildJsonArray {
                          for (stage in record.stages.sortedBy { it.start }) {
                            add(
                              buildJsonArray {
                                add(JsonPrimitive(stage.start.epochSecond))
                                add(JsonPrimitive(stage.end.epochSecond))
                                add(JsonPrimitive(stage.stage))
                              },
                            )
                          }
                        },
                      )
                    },
                  )
                }

                is HealthSkinTemperatureDetail -> {
                  add(
                    buildJsonObject {
                      put("start", record.start.epochSecond)
                      put("end", record.end.epochSecond)
                      record.origin?.let { originIndex[it] }?.let { put("o", it) }
                      record.baselineCelsius?.let { put("baselineC", rounded(it, 2)) }
                      record.location?.let { put("location", it) }
                      put(
                        "deltas",
                        buildJsonArray {
                          for ((time, delta) in record.deltas.sortedBy { it.first }) {
                            add(
                              buildJsonArray {
                                add(JsonPrimitive(time.epochSecond))
                                add(JsonPrimitive(rounded(delta, 2)))
                              },
                            )
                          }
                        },
                      )
                    },
                  )
                }
              }
            }
          },
        )
      }
      put("counts", buildJsonObject { results.forEach { (type, page) -> put(type.key, page.records.size) } })
      results.filterValues { it.truncated }.keys.takeIf { it.isNotEmpty() }?.let { truncated ->
        put("truncated", buildJsonArray { truncated.forEach { add(JsonPrimitive(it.key)) } })
      }
      if (missing.isNotEmpty()) put("missingPermissions", buildJsonArray { missing.forEach { add(JsonPrimitive(it.key)) } })
      if (unsupported.isNotEmpty()) put("unsupported", buildJsonArray { unsupported.forEach { add(JsonPrimitive(it.key)) } })
      if (errors.isNotEmpty()) put("readErrors", buildJsonObject { errors.toSortedMap().forEach { (key, message) -> put(key, message) } })
      put("backgroundAccess", HealthConnectPermissions.READ_IN_BACKGROUND in granted)
      put("historyAccess", HealthConnectPermissions.READ_HISTORY in granted)
      put("source", "healthConnect")
    }
  }

private fun readWindow(
  type: HealthSampleType,
  window: HealthWindow,
): HealthWindow =
  when (type) {
    HealthSampleType.Sleep, HealthSampleType.SkinTemperature -> HealthWindow(window.start.minus(HEALTH_SAMPLES_SLEEP_LOOKBACK), window.end)
    HealthSampleType.HeartRate, HealthSampleType.Steps -> HealthWindow(window.start.minus(HEALTH_SAMPLES_INTERVAL_SLACK), window.end.plus(HEALTH_SAMPLES_INTERVAL_SLACK))
    else -> window
  }

/**
 * Keeps each record on exactly one day: points by their time, steps by their start, and nightly
 * records (sleep, skin temperature) by the morning they ended, like the daily summary's sleep.
 */
private fun keepInWindow(
  type: HealthSampleType,
  records: List<HealthDetail>,
  window: HealthWindow,
): List<HealthDetail> {
  fun inWindow(time: Instant) = time >= window.start && time < window.end

  fun endedInWindow(end: Instant) = end > window.start && end <= window.end
  val kept =
    records.filter { record ->
      when (record) {
        is HealthPointDetail -> inWindow(record.time)
        is HealthStepsDetail -> inWindow(record.start)
        is HealthSleepDetail -> endedInWindow(record.end)
        is HealthSkinTemperatureDetail -> endedInWindow(record.end)
      }
    }
  return when (type) {
    HealthSampleType.Sleep, HealthSampleType.SkinTemperature -> kept.sortedBy { (it as? HealthSleepDetail)?.start ?: (it as HealthSkinTemperatureDetail).start }
    HealthSampleType.Steps -> kept.sortedBy { (it as HealthStepsDetail).start }
    else -> kept.sortedWith(compareBy({ (it as HealthPointDetail).time }, { it.origin }))
  }
}

private fun rounded(
  value: Double,
  decimals: Int,
): Double {
  val scale = 10.0.pow(decimals)
  return (value * scale).roundToLong() / scale
}

/** Health Connect sleep stage constants by readable name. */
internal fun healthSleepStageName(stage: Int): String =
  when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "rem"
    else -> "unknown"
  }

private fun skinTemperatureLocationName(location: Int): String? =
  when (location) {
    SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER -> "finger"
    SkinTemperatureRecord.MEASUREMENT_LOCATION_TOE -> "toe"
    SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST -> "wrist"
    else -> null
  }

private val Record.origin: String get() = metadata.dataOrigin.packageName

/** Health Connect reads for health.samples. */
internal suspend fun readHealthDetails(
  client: HealthConnectClient,
  window: HealthWindow,
  type: HealthSampleType,
): HealthDetailPage =
  when (type) {
    HealthSampleType.HeartRate -> {
      readPaged(client, HeartRateRecord::class, window) { record ->
        record.samples.map { HealthPointDetail(it.time, it.beatsPerMinute.toDouble(), record.origin) }
      }
    }

    HealthSampleType.Hrv -> {
      readPaged(client, HeartRateVariabilityRmssdRecord::class, window) { listOf(HealthPointDetail(it.time, it.heartRateVariabilityMillis, it.origin)) }
    }

    HealthSampleType.Spo2 -> {
      readPaged(client, OxygenSaturationRecord::class, window) { listOf(HealthPointDetail(it.time, it.percentage.value, it.origin)) }
    }

    HealthSampleType.RespiratoryRate -> {
      readPaged(client, RespiratoryRateRecord::class, window) { listOf(HealthPointDetail(it.time, it.rate, it.origin)) }
    }

    HealthSampleType.Steps -> {
      readPaged(client, StepsRecord::class, window) { listOf(HealthStepsDetail(it.startTime, it.endTime, it.count, it.origin)) }
    }

    HealthSampleType.Sleep -> {
      readPaged(client, SleepSessionRecord::class, window) { record ->
        listOf(
          HealthSleepDetail(
            start = record.startTime,
            end = record.endTime,
            origin = record.origin,
            title = record.title?.trim()?.ifEmpty { null },
            stages = record.stages.map { HealthSleepStageDetail(it.startTime, it.endTime, healthSleepStageName(it.stage)) },
          ),
        )
      }
    }

    HealthSampleType.SkinTemperature -> {
      readPaged(client, SkinTemperatureRecord::class, window) { record ->
        listOf(
          HealthSkinTemperatureDetail(
            start = record.startTime,
            end = record.endTime,
            origin = record.origin,
            baselineCelsius = record.baseline?.inCelsius,
            location = skinTemperatureLocationName(record.measurementLocation),
            deltas = record.deltas.map { it.time to it.delta.inCelsius },
          ),
        )
      }
    }
  }

private suspend fun <T : Record> readPaged(
  client: HealthConnectClient,
  type: KClass<T>,
  window: HealthWindow,
  map: (T) -> List<HealthDetail>,
): HealthDetailPage {
  val records = mutableListOf<HealthDetail>()
  var pageToken: String? = null
  var pages = 0
  do {
    val response =
      client.readRecords(
        ReadRecordsRequest(
          recordType = type,
          timeRangeFilter = TimeRangeFilter.between(window.start, window.end),
          pageSize = HEALTH_SAMPLES_PAGE_SIZE,
          pageToken = pageToken,
        ),
      )
    response.records.forEach { records += map(it) }
    pageToken = response.pageToken?.ifEmpty { null }
    pages += 1
  } while (pageToken != null && pages < HEALTH_SAMPLES_PAGE_LIMIT)
  return HealthDetailPage(records, truncated = pageToken != null)
}
