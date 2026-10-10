package ai.openclaw.app.node

import android.content.Context
import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class HealthHandlerTest : NodeHandlerRobolectricTest() {
  // 2026-10-10 12:00 in Tokyo; local midnight is 2026-10-09T15:00:00Z.
  private val clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneId.of("Asia/Tokyo"))

  private val lastNight =
    HealthSleepSession(
      start = Instant.parse("2026-10-09T14:00:00Z"),
      end = Instant.parse("2026-10-09T21:00:00Z"),
      asleep =
        listOf(
          window("2026-10-09T14:10:00Z", "2026-10-09T17:00:00Z"),
          // Overlapping stages from a second writer must not double count.
          window("2026-10-09T16:30:00Z", "2026-10-09T17:00:00Z"),
          window("2026-10-09T17:20:00Z", "2026-10-09T21:00:00Z"),
        ),
      dataOrigin = "com.fitbit.FitbitMobile",
    )

  private val workouts =
    listOf(
      HealthExerciseSession(
        Instant.parse("2026-10-10T00:00:00Z"),
        Instant.parse("2026-10-10T00:45:00Z"),
        "com.fitbit.FitbitMobile",
        type = "RUNNING",
        title = "Morning run",
      ),
      // Started before local midnight: belongs to yesterday, like HealthKit's strict start date.
      HealthExerciseSession(
        Instant.parse("2026-10-09T14:30:00Z"),
        Instant.parse("2026-10-09T15:30:00Z"),
        "com.google.android.apps.fitness",
        type = "WALKING",
      ),
    )

  @Test
  fun todaySummaryUsesTheIosPayloadShape() =
    runTest {
      val source =
        FakeHealthDataSource(
          aggregateFor = {
            HealthAggregate(
              stepCount = 4321,
              restingHeartRateBpm = 58.46,
              heartRateAvgBpm = 72,
              heartRateMinBpm = 50,
              heartRateMaxBpm = 130,
              dataOrigins = setOf("com.fitbit.FitbitMobile"),
            )
          },
          sleep = listOf(lastNight),
          exercise = workouts,
        )

      for (params in listOf(null, "{}", """{"period":"today"}""")) {
        val result = HealthHandler(appContext(), source, clock).handleHealthSummary(params)

        assertTrue(result.error?.message, result.ok)
        assertEquals(
          Json.parseToJsonElement(
            """
            {"period":"today","startISO":"2026-10-09T15:00:00Z","endISO":"2026-10-10T03:00:00Z",
             "timeZoneIdentifier":"Asia/Tokyo","stepCount":4321,"sleepDurationMinutes":340,
             "restingHeartRateBpm":58.5,"workoutCount":1,"workoutDurationMinutes":45,
             "heartRateAvgBpm":72,"heartRateMinBpm":50,"heartRateMaxBpm":130,
             "workouts":[{"type":"RUNNING","title":"Morning run","startISO":"2026-10-10T00:00:00Z",
                          "endISO":"2026-10-10T00:45:00Z","durationMinutes":45,"dataOrigin":"com.fitbit.FitbitMobile"}],
             "sleepSessions":[{"startISO":"2026-10-09T14:00:00Z","endISO":"2026-10-09T21:00:00Z","asleepMinutes":390}],
             "latestRecordISO":{"steps":null,"sleep":null,"heartRate":null,"restingHeartRate":null,"exercise":null},
             "backgroundAccess":true,"backgroundSupported":true,"historyAccess":false,
             "dataOrigins":["com.fitbit.FitbitMobile","com.google.android.apps.fitness"],"source":"healthConnect"}
            """,
          ),
          Json.parseToJsonElement(result.payloadJson ?: error("missing payload")),
        )
      }
    }

  @Test
  fun partialGrantReadsOnlyAllowedMetricsAndNamesTheRest() =
    runTest {
      val source =
        FakeHealthDataSource(
          granted = setOf(HealthMetric.Steps.readPermission),
          aggregateFor = { HealthAggregate(stepCount = 10) },
          sleep = listOf(lastNight),
          exercise = workouts,
        )

      val payload = payload(HealthHandler(appContext(), source, clock).handleHealthSummary("""{"period":"today"}"""))

      assertEquals(listOf(setOf(HealthMetric.Steps)), source.aggregateMetrics)
      assertTrue(source.sleepQueries.isEmpty())
      assertTrue(source.exerciseQueries.isEmpty())
      assertEquals(10, payload["stepCount"]?.jsonPrimitive?.int)
      assertNull(payload["sleepDurationMinutes"])
      assertNull(payload["sleepSessions"])
      // No exercise permission: absent, not an empty list.
      assertNull(payload["workouts"])
      assertEquals(
        listOf("sleep", "heartRate", "restingHeartRate", "exercise"),
        payload["missingPermissions"]?.jsonArray?.map { it.jsonPrimitive.content },
      )
      assertEquals("false", payload["backgroundAccess"]?.jsonPrimitive?.content)
    }

  @Test
  fun rangeAddsPerDayTotalsAndSplitsSleepAtMidnight() =
    runTest {
      val source =
        FakeHealthDataSource(
          aggregateFor = { window -> HealthAggregate(stepCount = Duration.between(window.start, window.end).toHours()) },
          sleep = listOf(lastNight),
          exercise = workouts,
        )

      val payload =
        payload(
          HealthHandler(appContext(), source, clock)
            .handleHealthSummary("""{"period":"range","startDate":"2026-10-08","endDate":"2026-10-10"}"""),
        )

      assertEquals("2026-10-07T15:00:00Z", payload["startISO"]?.jsonPrimitive?.content)
      assertEquals("2026-10-10T03:00:00Z", payload["endISO"]?.jsonPrimitive?.content)
      assertEquals("2026-10-08", payload["startDate"]?.jsonPrimitive?.content)
      assertEquals("2026-10-10", payload["endDate"]?.jsonPrimitive?.content)
      assertEquals(60, payload["stepCount"]?.jsonPrimitive?.int)
      assertEquals(390, payload["sleepDurationMinutes"]?.jsonPrimitive?.int)
      assertEquals(2, payload["workoutCount"]?.jsonPrimitive?.int)
      val days = payload["days"]?.jsonArray?.map { it.jsonObject } ?: error("missing days")
      assertEquals(listOf("2026-10-08", "2026-10-09", "2026-10-10"), days.map { it["date"]?.jsonPrimitive?.content })
      assertEquals(listOf(24, 24, 12), days.map { it["stepCount"]?.jsonPrimitive?.int })
      assertEquals(listOf(null, 50, 340), days.map { it["sleepDurationMinutes"]?.jsonPrimitive?.int })
      assertEquals(listOf(null, 1, 1), days.map { it["workoutCount"]?.jsonPrimitive?.int })
      // Same window rule as workoutCount; oldest first.
      assertEquals(
        listOf("WALKING", "RUNNING"),
        payload["workouts"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content },
      )
      assertEquals(
        listOf(emptyList(), listOf("WALKING"), listOf("RUNNING")),
        days.map { day -> day["workouts"]?.jsonArray?.map { it.jsonObject["type"]?.jsonPrimitive?.content } },
      )
      val walk = days[1]["workouts"]!!.jsonArray.single().jsonObject
      assertEquals(60, walk["durationMinutes"]?.jsonPrimitive?.int)
      assertEquals("com.google.android.apps.fitness", walk["dataOrigin"]?.jsonPrimitive?.content)
      assertFalse("title" in walk)
    }

  @Test
  fun yesterdayAndSingleDayCoverWholeLocalDays() {
    val yesterday = parseHealthSummaryRequest("""{"period":"yesterday"}""", clock)
    assertEquals(window("2026-10-08T15:00:00Z", "2026-10-09T15:00:00Z"), yesterday.window)

    val day = parseHealthSummaryRequest("""{"date":"2026-10-01"}""", clock)
    assertEquals("day", day.period)
    assertEquals(window("2026-09-30T15:00:00Z", "2026-10-01T15:00:00Z"), day.window)
  }

  @Test
  fun rejectsUnsupportedRequests() =
    runTest {
      val handler = HealthHandler(appContext(), FakeHealthDataSource(), clock)
      for (params in listOf(
        "[]",
        """{"period":"week"}""",
        """{"period":"day"}""",
        """{"period":"day","date":"2026-10-11"}""",
        """{"period":"day","date":"10/09/2026"}""",
        """{"period":"range","startDate":"2026-10-09","endDate":"2026-10-08"}""",
        """{"period":"range","startDate":"2026-09-01","endDate":"2026-10-10"}""",
      )) {
        assertEquals(params, "INVALID_REQUEST", handler.handleHealthSummary(params).error?.code)
      }
    }

  @Test
  fun explainsMissingProviderAndPermissions() =
    runTest {
      val noGrant = HealthHandler(appContext(), FakeHealthDataSource(granted = emptySet()), clock).handleHealthSummary(null)
      assertEquals("HEALTH_PERMISSION_REQUIRED", noGrant.error?.code)

      val unavailable =
        HealthHandler(appContext(), FakeHealthDataSource(availability = HealthConnectAvailability.Unavailable), clock)
          .handleHealthSummary(null)
      assertEquals("HEALTH_CONNECT_UNAVAILABLE", unavailable.error?.code)

      val update =
        HealthHandler(appContext(), FakeHealthDataSource(availability = HealthConnectAvailability.UpdateRequired), clock)
          .handleHealthSummary(null)
      assertEquals("HEALTH_CONNECT_UPDATE_REQUIRED", update.error?.code)

      val refused =
        HealthHandler(
          appContext(),
          FakeHealthDataSource(aggregateFor = { throw SecurityException("background read") }),
          clock,
        ).handleHealthSummary(null)
      assertFalse(refused.ok)
      assertEquals("HEALTH_PERMISSION_REQUIRED", refused.error?.code)
    }

  @Test
  fun backgroundRefusalSaysWhetherBackgroundReadsCanEverWork() =
    runTest {
      val foregroundOnly =
        HealthHandler(
          appContext(),
          FakeHealthDataSource(
            granted = HealthConnectPermissions.metricReads,
            access = HealthExtendedAccess(backgroundSupported = false, historySupported = false),
            aggregateFor = { throw SecurityException("background read") },
          ),
          clock,
        ).handleHealthSummary(null)
      assertEquals("HEALTH_FOREGROUND_ONLY", foregroundOnly.error?.code)

      val notAllowed =
        HealthHandler(
          appContext(),
          FakeHealthDataSource(
            granted = HealthConnectPermissions.metricReads,
            aggregateFor = { throw SecurityException("background read") },
          ),
          clock,
        ).handleHealthSummary(null)
      assertEquals("HEALTH_BACKGROUND_PERMISSION_REQUIRED", notAllowed.error?.code)
    }

  @Test
  fun reportsNewestRecordPerAllowedMetric() =
    runTest {
      val source =
        FakeHealthDataSource(
          granted =
            setOf(
              HealthMetric.Steps.readPermission,
              HealthMetric.Sleep.readPermission,
              HealthConnectPermissions.READ_IN_BACKGROUND,
              HealthConnectPermissions.READ_HISTORY,
            ),
          latest = mapOf(HealthMetric.Steps to Instant.parse("2026-10-10T02:45:00Z")),
        )
      val payload = payload(HealthHandler(appContext(), source, clock).handleHealthSummary(null))
      val latest = payload["latestRecordISO"]!!.jsonObject
      assertEquals("2026-10-10T02:45:00Z", latest["steps"]!!.jsonPrimitive.content)
      // Allowed but nothing readable: explicit null, unlike unallowed metrics which are absent.
      assertEquals(JsonNull, latest["sleep"])
      assertFalse("heartRate" in latest)
      assertEquals(listOf(Instant.parse("2026-10-10T03:00:00Z")), source.latestQueries)
      assertTrue(payload["historyAccess"]!!.jsonPrimitive.boolean)
      assertTrue(payload["backgroundSupported"]!!.jsonPrimitive.boolean)
    }

  @Test
  fun readableExerciseWithoutWorkoutsReportsAnEmptyList() =
    runTest {
      val payload = payload(HealthHandler(appContext(), FakeHealthDataSource(), clock).handleHealthSummary(null))
      assertEquals(0, payload["workouts"]?.jsonArray?.size)
      assertNull(payload["workoutCount"])
    }

  @Test
  fun exerciseTypesUseHealthConnectConstantNames() {
    assertEquals("WALKING", healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
    assertEquals("RUNNING", healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
    assertEquals("BIKING", healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
    assertEquals(
      "HIGH_INTENSITY_INTERVAL_TRAINING",
      healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING),
    )
    assertEquals("OTHER_WORKOUT", healthExerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
    assertEquals("OTHER_999", healthExerciseTypeName(999))
  }

  @Test
  fun mergedDurationClipsAndMergesOverlaps() {
    val merged =
      mergedDuration(
        listOf(
          window("2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z"),
          window("2026-10-10T00:30:00Z", "2026-10-10T02:00:00Z"),
          window("2026-10-10T03:00:00Z", "2026-10-10T05:00:00Z"),
        ),
        window("2026-10-10T00:15:00Z", "2026-10-10T04:00:00Z"),
      )
    assertEquals(Duration.ofMinutes(105 + 60), merged)
    assertNull(mergedDuration(emptyList(), window("2026-10-10T00:00:00Z", "2026-10-10T01:00:00Z")))
  }

  private fun payload(result: ai.openclaw.app.gateway.GatewaySession.InvokeResult): JsonObject {
    assertTrue(result.error?.message, result.ok)
    return Json.parseToJsonElement(result.payloadJson ?: error("missing payload")).jsonObject
  }

  private fun window(
    start: String,
    end: String,
  ) = HealthWindow(Instant.parse(start), Instant.parse(end))
}

private class FakeHealthDataSource(
  private val availability: HealthConnectAvailability = HealthConnectAvailability.Available,
  private val granted: Set<String> = HealthConnectPermissions.metricReads + HealthConnectPermissions.READ_IN_BACKGROUND,
  private val aggregateFor: (HealthWindow) -> HealthAggregate = { HealthAggregate() },
  private val sleep: List<HealthSleepSession> = emptyList(),
  private val exercise: List<HealthExerciseSession> = emptyList(),
  private val access: HealthExtendedAccess = HealthExtendedAccess(backgroundSupported = true, historySupported = true),
  private val latest: Map<HealthMetric, Instant> = emptyMap(),
) : HealthDataSource {
  val aggregateMetrics = mutableListOf<Set<HealthMetric>>()
  val sleepQueries = mutableListOf<HealthWindow>()
  val exerciseQueries = mutableListOf<HealthWindow>()
  val latestQueries = mutableListOf<Instant>()

  override fun availability(context: Context): HealthConnectAvailability = availability

  override fun extendedAccessSupport(context: Context): HealthExtendedAccess = access

  override suspend fun grantedPermissions(context: Context): Set<String> = granted

  override suspend fun latestRecordTimes(
    context: Context,
    metrics: Set<HealthMetric>,
    before: Instant,
  ): Map<HealthMetric, Instant?> {
    latestQueries += before
    return metrics.associateWith { latest[it] }
  }

  override suspend fun aggregate(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthAggregate {
    aggregateMetrics += metrics
    return aggregateFor(window)
  }

  override suspend fun sleepSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthSleepSession> {
    sleepQueries += window
    return sleep
  }

  override suspend fun exerciseSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthExerciseSession> {
    exerciseQueries += window
    return exercise
  }
}
