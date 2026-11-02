package ai.openclaw.app.node

import android.content.Context
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

  // Every type the fake provider supports (feature-gated ones are unsupported by default).
  private val allLatestNull =
    HealthMetric.entries.filter { it.feature == null }.joinToString(",", "{", "}") { "\"${it.key}\":null" }

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
             "latestRecordISO":$allLatestNull,
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

      // Steps cadence shares READ_STEPS.
      assertEquals(listOf(setOf(HealthMetric.Steps, HealthMetric.StepsCadence)), source.aggregateMetrics)
      assertTrue(source.sleepQueries.isEmpty())
      assertTrue(source.exerciseQueries.isEmpty())
      assertEquals(10, payload["stepCount"]?.jsonPrimitive?.int)
      assertNull(payload["sleepDurationMinutes"])
      assertNull(payload["sleepSessions"])
      // No exercise permission: absent, not an empty list.
      assertNull(payload["workouts"])
      assertEquals(
        HealthMetric.entries
          .filter { it.feature == null && it != HealthMetric.Steps && it != HealthMetric.StepsCadence }
          .map { it.key },
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
      assertEquals(setOf(Instant.parse("2026-10-10T03:00:00Z")), source.latestQueries.toSet())
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

  private val routedWorkouts =
    listOf(
      HealthExerciseSession(
        Instant.parse("2026-10-10T00:00:00Z"),
        Instant.parse("2026-10-10T00:30:00Z"),
        "com.fitbit.FitbitMobile",
        type = "RUNNING",
        route =
          HealthRoute(
            HealthRouteStatus.Data,
            listOf(
              HealthRoutePoint(Instant.parse("2026-10-10T00:00:00Z"), 0.0, 0.0, altitudeMeters = 12.5, horizontalAccuracyMeters = 4.0),
              HealthRoutePoint(Instant.parse("2026-10-10T00:30:00Z"), 0.0, 0.01),
            ),
          ),
      ),
      HealthExerciseSession(
        Instant.parse("2026-10-10T01:00:00Z"),
        Instant.parse("2026-10-10T01:20:00Z"),
        "com.fitbit.FitbitMobile",
        type = "WALKING",
        route = HealthRoute(HealthRouteStatus.ConsentRequired),
      ),
      HealthExerciseSession(
        Instant.parse("2026-10-10T02:00:00Z"),
        Instant.parse("2026-10-10T02:40:00Z"),
        "com.fitbit.FitbitMobile",
        type = "STRENGTH_TRAINING",
      ),
    )

  private val routeGrant =
    HealthConnectPermissions.metricReads + HealthConnectPermissions.READ_IN_BACKGROUND + HealthConnectPermissions.READ_EXERCISE_ROUTES

  @Test
  fun routeStatusFollowsTheRouteResultAndHidesPointsByDefault() =
    runTest {
      val source = FakeHealthDataSource(granted = routeGrant, exercise = routedWorkouts)
      val workouts = workouts(HealthHandler(appContext(), source, clock).handleHealthSummary(null))

      assertEquals(listOf("data", "consent_required", "none"), workouts.map { it["routeStatus"]?.jsonPrimitive?.content })
      assertEquals(2, workouts[0]["routePointCount"]?.jsonPrimitive?.int)
      // 0.01° of longitude on the equator.
      assertEquals(1112L, workouts[0]["routeDistanceMeters"]?.jsonPrimitive?.long)
      assertNull(workouts[0]["route"])
      for (workout in workouts.drop(1)) {
        assertNull(workout["routePointCount"])
        assertNull(workout["routeDistanceMeters"])
        assertNull(workout["route"])
      }
    }

  @Test
  fun includeRoutesAddsThePointsOfDataRoutesOnly() =
    runTest {
      val source = FakeHealthDataSource(granted = routeGrant, exercise = routedWorkouts)
      val workouts =
        workouts(
          HealthHandler(appContext(), source, clock)
            .handleHealthSummary("""{"period":"range","startDate":"2026-10-09","endDate":"2026-10-10","includeRoutes":true}"""),
        )

      assertEquals(
        Json.parseToJsonElement(
          """
          [{"t":"2026-10-10T00:00:00Z","lat":0.0,"lon":0.0,"alt":12.5,"acc":4.0},
           {"t":"2026-10-10T00:30:00Z","lat":0.0,"lon":0.01}]
          """,
        ),
        workouts[0]["route"],
      )
      assertNull(workouts[1]["route"])
      assertNull(workouts[2]["route"])
    }

  @Test
  fun routeFieldsStayAbsentWithoutTheRoutePermission() =
    runTest {
      val source = FakeHealthDataSource(exercise = routedWorkouts)
      val workouts =
        workouts(HealthHandler(appContext(), source, clock).handleHealthSummary("""{"includeRoutes":true}"""))

      assertEquals(3, workouts.size)
      for (workout in workouts) {
        for (key in listOf("routeStatus", "routePointCount", "routeDistanceMeters", "route")) {
          assertFalse(key, key in workout)
        }
      }
    }

  @Test
  fun routeResultsMapToTheThreeStatuses() {
    val route =
      healthRoute(
        ExerciseRouteResult.Data(
          ExerciseRoute(
            listOf(
              ExerciseRoute.Location(
                time = Instant.parse("2026-10-10T00:00:00Z"),
                latitude = 35.0,
                longitude = 139.0,
                horizontalAccuracy = Length.meters(3.0),
                altitude = Length.meters(40.0),
              ),
            ),
          ),
        ),
      )
    assertEquals(HealthRouteStatus.Data, route.status)
    assertEquals(
      listOf(HealthRoutePoint(Instant.parse("2026-10-10T00:00:00Z"), 35.0, 139.0, altitudeMeters = 40.0, horizontalAccuracyMeters = 3.0)),
      route.points,
    )
    assertEquals(HealthRoute(HealthRouteStatus.ConsentRequired), healthRoute(ExerciseRouteResult.ConsentRequired()))
    assertEquals(HealthRoute(HealthRouteStatus.None), healthRoute(ExerciseRouteResult.NoData()))
  }

  @Test
  fun routeDistanceIsHaversineAlongThePoints() {
    fun point(
      lat: Double,
      lon: Double,
    ) = HealthRoutePoint(Instant.parse("2026-10-10T00:00:00Z"), lat, lon)

    assertEquals(0.0, routeDistanceMeters(emptyList()), 0.0)
    assertEquals(0.0, routeDistanceMeters(listOf(point(35.0, 139.0))), 0.0)
    // One degree of latitude on the mean Earth radius, walked out and back.
    assertEquals(2 * 111_195.08, routeDistanceMeters(listOf(point(0.0, 0.0), point(1.0, 0.0), point(0.0, 0.0))), 0.1)
    // Tokyo Station → Shinjuku Station, about 6.1 km in a straight line.
    assertEquals(6_134.4, routeDistanceMeters(listOf(point(35.681236, 139.767125), point(35.690921, 139.700258))), 1.0)
  }

  @Test
  fun routePermissionIsRequestedOnlyWhereHealthConnectKnowsIt() {
    assertTrue(
      HealthConnectPermissions.READ_EXERCISE_ROUTES in
        HealthConnectPermissions.requestSet(backgroundSupported = true, historySupported = true, routesSupported = true),
    )
    assertFalse(
      HealthConnectPermissions.READ_EXERCISE_ROUTES in
        HealthConnectPermissions.requestSet(backgroundSupported = true, historySupported = true, routesSupported = false),
    )
    assertTrue(parseHealthSummaryRequest("""{"includeRoutes":true}""", clock).includeRoutes)
    assertFalse(parseHealthSummaryRequest(null, clock).includeRoutes)
  }

  @Test
  fun newTypesAddUnitNamedFieldsPerWindowAndDay() =
    runTest {
      val yesterday = window("2026-10-08T15:00:00Z", "2026-10-09T15:00:00Z")
      val source =
        FakeHealthDataSource(
          totalsFor = { w, _ ->
            val dayOne = w == yesterday
            HealthTotals(
              values =
                mapOf(
                  "totalCaloriesKcal" to if (dayOne) 2100.4 else 900.6,
                  "distanceMeters" to if (dayOne) 5012.7 else 1500.2,
                  "floorsClimbed" to 3.04,
                  "bloodPressureSystolicAvgMmHg" to 118.6,
                  "nutritionProteinGrams" to 61.25,
                  "hydrationMilliliters" to 1250.4,
                ),
              dataOrigins = setOf("com.google.android.apps.fitness"),
            )
          },
          samplesFor = { metric ->
            when (metric) {
              HealthMetric.Weight -> {
                listOf(
                  HealthSample(Instant.parse("2026-10-09T00:00:00Z"), 70.456),
                  HealthSample(Instant.parse("2026-10-09T12:00:00Z"), 70.123),
                  HealthSample(Instant.parse("2026-10-09T23:00:00Z"), 69.9),
                )
              }

              HealthMetric.OxygenSaturation -> {
                listOf(
                  HealthSample(Instant.parse("2026-10-09T01:00:00Z"), 95.0),
                  HealthSample(Instant.parse("2026-10-09T02:00:00Z"), 98.0),
                  HealthSample(Instant.parse("2026-10-10T01:00:00Z"), 97.0),
                )
              }

              else -> {
                emptyList()
              }
            }
          },
        )

      val payload =
        payload(
          HealthHandler(appContext(), source, clock)
            .handleHealthSummary("""{"period":"range","startDate":"2026-10-09","endDate":"2026-10-10"}"""),
        )
      val days = payload["days"]!!.jsonArray.map { it.jsonObject }

      // Health Connect's totals, rounded to each field's precision.
      assertEquals(2100L, days[0]["totalCaloriesKcal"]?.jsonPrimitive?.long)
      assertEquals(901L, days[1]["totalCaloriesKcal"]?.jsonPrimitive?.long)
      assertEquals(5013L, days[0]["distanceMeters"]?.jsonPrimitive?.long)
      assertEquals("3.0", days[0]["floorsClimbed"]?.jsonPrimitive?.content)
      assertEquals(119L, days[0]["bloodPressureSystolicAvgMmHg"]?.jsonPrimitive?.long)
      assertEquals("61.3", days[0]["nutritionProteinGrams"]?.jsonPrimitive?.content)
      assertEquals(1250L, payload["hydrationMilliliters"]?.jsonPrimitive?.long)
      // Latest value per window; a day without one leaves the field out.
      assertEquals("70.12", days[0]["weightKg"]?.jsonPrimitive?.content)
      assertEquals("69.9", days[1]["weightKg"]?.jsonPrimitive?.content)
      assertEquals("69.9", payload["weightKg"]?.jsonPrimitive?.content)
      // Avg / min / max over the window's samples.
      assertEquals("96.5", days[0]["oxygenSaturationAvgPercent"]?.jsonPrimitive?.content)
      assertEquals("95.0", days[0]["oxygenSaturationMinPercent"]?.jsonPrimitive?.content)
      assertEquals("98.0", days[0]["oxygenSaturationMaxPercent"]?.jsonPrimitive?.content)
      assertEquals("96.7", payload["oxygenSaturationAvgPercent"]?.jsonPrimitive?.content)
      assertFalse("heightMeters" in payload)
      assertTrue("com.google.android.apps.fitness" in payload["dataOrigins"]!!.jsonArray.map { it.jsonPrimitive.content })
      assertNull(payload["readErrors"])
      // Totals for every type travel in one aggregate call per window.
      assertEquals(3, source.totalsQueries.size)
    }

  @Test
  fun typesWithoutPermissionOrFeatureStayOut() =
    runTest {
      val source =
        FakeHealthDataSource(
          granted = setOf(HealthMetric.Weight.readPermission, HealthMetric.SkinTemperature.readPermission),
          totalsFor = { _, _ -> HealthTotals(mapOf("skinTemperatureDeltaAvgCelsius" to -0.234, "distanceMeters" to 10.0)) },
          samplesFor = { listOf(HealthSample(Instant.parse("2026-10-10T00:00:00Z"), 70.0)) },
        )

      val unsupported = payload(HealthHandler(appContext(), source, clock).handleHealthSummary(null))
      assertEquals("70.0", unsupported["weightKg"]?.jsonPrimitive?.content)
      assertFalse("distanceMeters" in unsupported)
      assertFalse("skinTemperatureDeltaAvgCelsius" in unsupported)
      val missing = unsupported["missingPermissions"]!!.jsonArray.map { it.jsonPrimitive.content }
      assertTrue("distance" in missing)
      // A feature this provider lacks is not a missing permission.
      assertFalse("skinTemperature" in missing)
      assertFalse("plannedExercise" in missing)
      assertEquals(listOf(HealthMetric.Weight), source.sampleQueries)

      val supportedSource =
        FakeHealthDataSource(
          granted = setOf(HealthMetric.SkinTemperature.readPermission),
          access = HealthExtendedAccess(true, true, supportedFeatures = setOf(HealthMetric.SkinTemperature.feature!!)),
          totalsFor = { _, _ -> HealthTotals(mapOf("skinTemperatureDeltaAvgCelsius" to -0.234)) },
        )
      val supported = payload(HealthHandler(appContext(), supportedSource, clock).handleHealthSummary(null))
      assertEquals("-0.23", supported["skinTemperatureDeltaAvgCelsius"]?.jsonPrimitive?.content)
    }

  @Test
  fun oneFailingTypeIsNamedWithoutFailingTheSummary() =
    runTest {
      val source =
        FakeHealthDataSource(
          aggregateFor = { HealthAggregate(stepCount = 10) },
          failing = setOf(HealthMetric.Distance, HealthMetric.Weight),
          totalsFor = { _, types ->
            HealthTotals(
              buildMap {
                if (HealthMetric.TotalCalories in types) put("totalCaloriesKcal", 2000.0)
                if (HealthMetric.Hydration in types) put("hydrationMilliliters", 800.0)
              },
            )
          },
          samplesFor = { listOf(HealthSample(Instant.parse("2026-10-10T00:00:00Z"), 1.72)) },
        )

      val payload = payload(HealthHandler(appContext(), source, clock).handleHealthSummary(null))

      assertEquals(10, payload["stepCount"]?.jsonPrimitive?.int)
      // The combined call failed on distance; the per-type retry keeps the rest.
      assertEquals(2000L, payload["totalCaloriesKcal"]?.jsonPrimitive?.long)
      assertEquals(800L, payload["hydrationMilliliters"]?.jsonPrimitive?.long)
      assertFalse("distanceMeters" in payload)
      assertFalse("weightKg" in payload)
      assertEquals("1.72", payload["heightMeters"]?.jsonPrimitive?.content)
      assertEquals(
        mapOf("distance" to "distance unavailable", "weight" to "weight unavailable"),
        payload["readErrors"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
      )
    }

  @Test
  fun workoutsCarryTheirOwnTotals() =
    runTest {
      val run = workouts.first()
      val source =
        FakeHealthDataSource(
          exercise = workouts,
          totalsFor = { w, _ ->
            if (w == HealthWindow(run.start, run.end)) {
              HealthTotals(
                mapOf("totalCaloriesKcal" to 420.4, "activeCaloriesKcal" to 350.6, "distanceMeters" to 7020.2, "elevationGainedMeters" to 35.25),
              )
            } else {
              HealthTotals()
            }
          },
        )

      val workout = workouts(HealthHandler(appContext(), source, clock).handleHealthSummary(null)).single()

      assertEquals(420L, workout["totalCaloriesKcal"]?.jsonPrimitive?.long)
      assertEquals(351L, workout["activeCaloriesKcal"]?.jsonPrimitive?.long)
      assertEquals(7020L, workout["distanceMeters"]?.jsonPrimitive?.long)
      assertEquals("35.3", workout["elevationGainedMeters"]?.jsonPrimitive?.content)
      val workoutQuery = source.totalsQueries.single { it.first == HealthWindow(run.start, run.end) }
      assertEquals(HEALTH_WORKOUT_TOTAL_METRICS.toSet(), workoutQuery.second)
    }

  @Test
  fun plannedWorkoutsNeedTheFeatureAndPermission() =
    runTest {
      val plan =
        HealthPlannedExercise(Instant.parse("2026-10-10T09:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"), "RUNNING", "Tempo", completed = false)
      val withFeature =
        FakeHealthDataSource(
          granted = setOf(HealthMetric.PlannedExercise.readPermission),
          access = HealthExtendedAccess(true, true, supportedFeatures = setOf(HealthMetric.PlannedExercise.feature!!)),
          planned = listOf(plan),
        )
      // Planned workouts later today still belong to today.
      val laterClock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneId.of("Asia/Tokyo"))
      val payload = payload(HealthHandler(appContext(), withFeature, laterClock).handleHealthSummary(null))
      assertEquals(
        Json.parseToJsonElement(
          """[{"type":"RUNNING","title":"Tempo","startISO":"2026-10-10T09:00:00Z","endISO":"2026-10-10T10:00:00Z","completed":false}]""",
        ),
        payload["plannedWorkouts"],
      )

      val withoutFeature = FakeHealthDataSource(granted = setOf(HealthMetric.PlannedExercise.readPermission, HealthMetric.Steps.readPermission), planned = listOf(plan))
      assertNull(payload(HealthHandler(appContext(), withoutFeature, laterClock).handleHealthSummary(null))["plannedWorkouts"])
    }

  @Test
  fun readingRulesConvertHealthConnectRecords() {
    val at = Instant.parse("2026-10-10T00:00:00Z")

    fun sample(
      metric: HealthMetric,
      record: androidx.health.connect.client.records.Record,
    ): HealthSample =
      when (val reading = HEALTH_READINGS.getValue(metric)) {
        is HealthReading.Latest -> reading.sample(record)
        is HealthReading.Stats -> reading.sample(record)
        is HealthReading.Totals -> error("aggregated by Health Connect")
      }
    val meta = Metadata.manualEntry()

    assertEquals(70.25, sample(HealthMetric.Weight, WeightRecord(at, null, Mass.kilograms(70.25), meta)).value, 1e-9)
    assertEquals(at, sample(HealthMetric.Weight, WeightRecord(at, null, Mass.kilograms(70.25), meta)).time)
    assertEquals(21.5, sample(HealthMetric.BodyFat, BodyFatRecord(at, null, Percentage(21.5), meta)).value, 1e-9)
    assertEquals(1520.0, sample(HealthMetric.BasalMetabolicRate, BasalMetabolicRateRecord(at, null, Power.kilocaloriesPerDay(1520.0), meta)).value, 1e-6)
    assertEquals(97.0, sample(HealthMetric.OxygenSaturation, OxygenSaturationRecord(at, null, Percentage(97.0), meta)).value, 1e-9)
    assertEquals(
      100.0,
      sample(HealthMetric.BloodGlucose, BloodGlucoseRecord(at, null, meta, BloodGlucose.milligramsPerDeciliter(100.0))).value,
      1e-6,
    )
    assertEquals(42.0, sample(HealthMetric.HeartRateVariability, HeartRateVariabilityRmssdRecord(at, null, 42.0, meta)).value, 1e-9)
    assertEquals(45.5, sample(HealthMetric.Vo2Max, Vo2MaxRecord(at, null, meta, 45.5)).value, 1e-9)
    // Every new type has exactly one reading rule; the original five and planned workouts have their own code.
    val bespoke = setOf(HealthMetric.Steps, HealthMetric.Sleep, HealthMetric.HeartRate, HealthMetric.RestingHeartRate, HealthMetric.Exercise, HealthMetric.PlannedExercise)
    assertEquals(HealthMetric.entries.toSet() - bespoke, HEALTH_READINGS.keys)
  }

  @Test
  fun sampleStatsAndLatestStayInsideTheWindow() {
    val day = window("2026-10-09T15:00:00Z", "2026-10-10T15:00:00Z")
    val samples =
      listOf(
        HealthSample(Instant.parse("2026-10-09T14:59:59Z"), 1.0),
        HealthSample(Instant.parse("2026-10-09T15:00:00Z"), 4.0),
        HealthSample(Instant.parse("2026-10-10T03:00:00Z"), 2.0),
        HealthSample(Instant.parse("2026-10-10T15:00:00Z"), 9.0),
      )
    assertEquals(HealthSampleStats(avg = 3.0, min = 2.0, max = 4.0), sampleStats(samples, day))
    assertEquals(2.0, latestSample(samples, day)?.value)
    assertNull(sampleStats(emptyList(), day))
    assertNull(latestSample(samples.take(1), day))
  }

  @Test
  fun permissionsAreDeclaredAndLeaveReproductiveDataOut() {
    @Suppress("DEPRECATION")
    val declared =
      appContext()
        .packageManager
        .getPackageInfo(appContext().packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()
        .toSet()
    assertEquals(emptySet<String>(), HealthConnectPermissions.metricReads - declared)
    val reproductive = listOf("MENSTRUATION", "OVULATION", "CERVICAL_MUCUS", "INTERMENSTRUAL", "SEXUAL_ACTIVITY")
    assertTrue((HealthConnectPermissions.metricReads + declared).none { permission -> reproductive.any { it in permission } })
    // Feature-gated reads are requested only where the provider has the feature.
    val skin = HealthMetric.SkinTemperature.readPermission
    assertFalse(skin in HealthConnectPermissions.requestSet(backgroundSupported = true, historySupported = true))
    assertTrue(
      skin in HealthConnectPermissions.requestSet(true, true, supportedFeatures = setOf(HealthMetric.SkinTemperature.feature!!)),
    )
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

  @Test
  fun samplesForADayReturnCompactRowsAndSleepStagesKeptOnOneDay() =
    runTest {
      val fitbit = "com.fitbit.FitbitMobile"
      val fit = "com.google.android.apps.fitness"
      val source =
        FakeHealthDataSource(
          detailsFor = { type, _ ->
            when (type) {
              HealthSampleType.HeartRate -> {
                HealthDetailPage(
                  listOf(
                    HealthPointDetail(Instant.parse("2026-10-09T03:00:00Z"), 80.4, fit),
                    // Before local midnight and at the next midnight: other days.
                    HealthPointDetail(Instant.parse("2026-10-08T14:59:59Z"), 70.0, fitbit),
                    HealthPointDetail(Instant.parse("2026-10-09T15:00:00Z"), 70.0, fitbit),
                    HealthPointDetail(Instant.parse("2026-10-08T15:00:00Z"), 61.0, fitbit),
                  ),
                )
              }

              HealthSampleType.Sleep -> {
                HealthDetailPage(
                  listOf(
                    HealthSleepDetail(
                      Instant.parse("2026-10-08T14:00:00Z"),
                      Instant.parse("2026-10-08T21:00:00Z"),
                      fitbit,
                      stages =
                        listOf(
                          HealthSleepStageDetail(Instant.parse("2026-10-08T16:00:00Z"), Instant.parse("2026-10-08T18:00:00Z"), "deep"),
                          HealthSleepStageDetail(Instant.parse("2026-10-08T14:00:00Z"), Instant.parse("2026-10-08T16:00:00Z"), "light"),
                        ),
                    ),
                    // Ended the morning before: belongs to 2026-10-08.
                    HealthSleepDetail(Instant.parse("2026-10-07T14:00:00Z"), Instant.parse("2026-10-07T21:00:00Z"), fitbit),
                  ),
                )
              }

              HealthSampleType.Steps -> {
                HealthDetailPage(
                  listOf(
                    HealthStepsDetail(Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-09T00:01:00Z"), 120, fit),
                    HealthStepsDetail(Instant.parse("2026-10-08T14:58:00Z"), Instant.parse("2026-10-08T15:01:00Z"), 30, fit),
                  ),
                  truncated = true,
                )
              }

              else -> {
                HealthDetailPage(emptyList())
              }
            }
          },
        )

      val payload = payload(HealthHandler(appContext(), source, clock).handleHealthSamples("""{"date":"2026-10-09"}"""))

      assertEquals("2026-10-09", payload["date"]?.jsonPrimitive?.content)
      assertEquals("2026-10-08T15:00:00Z", payload["startISO"]?.jsonPrimitive?.content)
      assertEquals("2026-10-09T15:00:00Z", payload["endISO"]?.jsonPrimitive?.content)
      assertEquals(Json.parseToJsonElement("""["$fitbit","$fit"]"""), payload["origins"])
      assertEquals(Json.parseToJsonElement("[[1791471600,61,0],[1791514800,80,1]]"), payload["heartRate"])
      assertEquals(Json.parseToJsonElement("""["t","bpm","o"]"""), payload["fields"]?.jsonObject?.get("heartRate"))
      assertEquals(Json.parseToJsonElement("[[1791504000,1791504060,120,1]]"), payload["steps"])
      assertEquals(
        Json.parseToJsonElement(
          """
          [{"start":1791468000,"end":1791493200,"startISO":"2026-10-08T14:00:00Z","endISO":"2026-10-08T21:00:00Z","o":0,
            "stages":[[1791468000,1791475200,"light"],[1791475200,1791482400,"deep"]]}]
          """,
        ),
        payload["sleep"],
      )
      assertEquals(Json.parseToJsonElement("""["steps"]"""), payload["truncated"])
      assertEquals(
        2,
        payload["counts"]
          ?.jsonObject
          ?.get("heartRate")
          ?.jsonPrimitive
          ?.int,
      )
      // Skin temperature is feature-gated and the fake provider does not support it.
      assertEquals(Json.parseToJsonElement("""["skinTemperature"]"""), payload["unsupported"])
      assertNull(payload["skinTemperature"])
      // Sleep reads a day back; heart rate and steps read with slack on both sides.
      val windows = source.detailQueries.toMap()
      assertEquals(window("2026-10-07T15:00:00Z", "2026-10-09T15:00:00Z"), windows[HealthSampleType.Sleep])
      assertEquals(window("2026-10-08T12:00:00Z", "2026-10-09T18:00:00Z"), windows[HealthSampleType.HeartRate])
      assertEquals(window("2026-10-08T15:00:00Z", "2026-10-09T15:00:00Z"), windows[HealthSampleType.Hrv])
    }

  @Test
  fun samplesHonorTypesAndLeaveOutTypesWithoutPermission() =
    runTest {
      val source =
        FakeHealthDataSource(
          granted = setOf(HealthMetric.HeartRate.readPermission, HealthConnectPermissions.READ_IN_BACKGROUND),
          detailsFor = { _, _ -> HealthDetailPage(emptyList()) },
        )

      val payload =
        payload(
          HealthHandler(appContext(), source, clock).handleHealthSamples(
            """{"startISO":"2026-10-09T09:00:00+09:00","endISO":"2026-10-09T21:00:00+09:00","types":["sleep","heartRate"]}""",
          ),
        )

      assertEquals(listOf(HealthSampleType.HeartRate), source.detailQueries.map { it.first })
      assertNull(payload["date"])
      assertEquals("2026-10-09T00:00:00Z", payload["startISO"]?.jsonPrimitive?.content)
      assertEquals(Json.parseToJsonElement("[]"), payload["heartRate"])
      assertEquals(Json.parseToJsonElement("""["sleep"]"""), payload["missingPermissions"])
      assertNull(payload["sleep"])
      assertNull(payload["steps"])
    }

  @Test
  fun samplesRejectBadRangesAndUnknownTypes() =
    runTest {
      val handler = HealthHandler(appContext(), FakeHealthDataSource(), clock)
      for (
      params in
      listOf(
        """{"date":"2026-10-11"}""",
        """{"startISO":"2026-10-08T00:00:00Z","endISO":"2026-10-09T00:00:01Z"}""",
        """{"endISO":"2026-10-09T00:00:00Z"}""",
        """{"date":"2026-10-09","startISO":"2026-10-09T00:00:00Z"}""",
        """{"types":["heartRate","blood"]}""",
      )
      ) {
        assertEquals(params, "INVALID_REQUEST", handler.handleHealthSamples(params).error?.code)
      }
    }

  @Test
  fun aDayOfOneHertzHeartRateFitsThePayloadCap() =
    runTest {
      val start = Instant.parse("2026-10-08T15:00:00Z")
      val source =
        FakeHealthDataSource(
          detailsFor = { type, _ ->
            if (type == HealthSampleType.HeartRate) {
              HealthDetailPage((0 until 86_400).map { HealthPointDetail(start.plusSeconds(it.toLong()), 60.0 + it % 90, "com.fitbit.FitbitMobile") })
            } else {
              HealthDetailPage(emptyList())
            }
          },
        )

      val result = HealthHandler(appContext(), source, clock).handleHealthSamples("""{"date":"2026-10-09","types":"heartRate"}""")

      val size = payload(result).toString().length
      assertEquals(
        86_400,
        payload(result)["counts"]
          ?.jsonObject
          ?.get("heartRate")
          ?.jsonPrimitive
          ?.int,
      )
      // About 17 bytes a sample: roughly 1.5 MB for a full day at 1 Hz.
      assertTrue("$size bytes", size in 1_000_000 until HEALTH_SAMPLES_MAX_PAYLOAD_BYTES)
    }

  private fun payload(result: ai.openclaw.app.gateway.GatewaySession.InvokeResult): JsonObject {
    assertTrue(result.error?.message, result.ok)
    return Json.parseToJsonElement(result.payloadJson ?: error("missing payload")).jsonObject
  }

  private fun workouts(result: ai.openclaw.app.gateway.GatewaySession.InvokeResult): List<JsonObject> =
    payload(result)["workouts"]
      ?.jsonArray
      ?.map { it.jsonObject } ?: error("missing workouts")

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
  private val totalsFor: (HealthWindow, Set<HealthMetric>) -> HealthTotals = { _, _ -> HealthTotals() },
  private val samplesFor: (HealthMetric) -> List<HealthSample> = { emptyList() },
  private val planned: List<HealthPlannedExercise> = emptyList(),
  private val failing: Set<HealthMetric> = emptySet(),
  private val detailsFor: (HealthSampleType, HealthWindow) -> HealthDetailPage = { _, _ -> HealthDetailPage(emptyList()) },
) : HealthDataSource {
  val detailQueries = mutableListOf<Pair<HealthSampleType, HealthWindow>>()

  val totalsQueries = mutableListOf<Pair<HealthWindow, Set<HealthMetric>>>()
  val sampleQueries = mutableListOf<HealthMetric>()

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
    synchronized(latestQueries) { latestQueries += before }
    return metrics.associateWith { latest[it] }
  }

  override suspend fun aggregate(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthAggregate {
    synchronized(aggregateMetrics) { aggregateMetrics += metrics }
    return aggregateFor(window)
  }

  override suspend fun sleepSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthSleepSession> {
    synchronized(sleepQueries) { sleepQueries += window }
    return sleep
  }

  override suspend fun exerciseSessions(
    context: Context,
    window: HealthWindow,
  ): List<HealthExerciseSession> {
    synchronized(exerciseQueries) { exerciseQueries += window }
    return exercise
  }

  override suspend fun totals(
    context: Context,
    window: HealthWindow,
    metrics: Set<HealthMetric>,
  ): HealthTotals {
    synchronized(totalsQueries) { totalsQueries += window to metrics }
    metrics.firstOrNull { it in failing }?.let { throw IllegalStateException("${it.key} unavailable") }
    return totalsFor(window, metrics)
  }

  override suspend fun samples(
    context: Context,
    window: HealthWindow,
    metric: HealthMetric,
  ): List<HealthSample> {
    synchronized(sampleQueries) { sampleQueries += metric }
    if (metric in failing) throw IllegalStateException("${metric.key} unavailable")
    return samplesFor(metric)
  }

  override suspend fun plannedExercises(
    context: Context,
    window: HealthWindow,
  ): List<HealthPlannedExercise> {
    if (HealthMetric.PlannedExercise in failing) throw IllegalStateException("plannedExercise unavailable")
    return planned
  }

  override suspend fun detailRecords(
    context: Context,
    window: HealthWindow,
    type: HealthSampleType,
  ): HealthDetailPage {
    synchronized(detailQueries) { detailQueries += type to window }
    if (type.metric in failing) throw IllegalStateException("${type.key} unavailable")
    return detailsFor(type, window)
  }
}
