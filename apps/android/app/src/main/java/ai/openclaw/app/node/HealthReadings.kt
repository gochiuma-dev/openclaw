package ai.openclaw.app.node

import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
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
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.math.pow
import kotlin.math.roundToLong

/** One measurement from a type Health Connect cannot aggregate, as a plain number in the field's unit. */
internal data class HealthSample(
  val time: Instant,
  val value: Double,
  val dataOrigin: String? = null,
)

/** Health Connect's own totals and averages for a window, keyed by payload field. */
internal data class HealthTotals(
  val values: Map<String, Double> = emptyMap(),
  val dataOrigins: Set<String> = emptySet(),
)

internal class HealthAggregateField<T : Any>(
  val key: String,
  val decimals: Int,
  val metric: AggregateMetric<T>,
  private val value: (T) -> Double,
) {
  fun read(result: AggregationResult): Double? = result[metric]?.let(value)
}

/** How health.summary turns one Health Connect type into payload fields. */
internal sealed interface HealthReading {
  /** Summed or averaged by Health Connect's aggregate API, which drops overlapping writers. */
  class Totals(
    val fields: List<HealthAggregateField<*>>,
  ) : HealthReading

  /** Spot measurements where only the newest value in the window matters (body composition). */
  class Latest(
    val key: String,
    val decimals: Int,
    val sample: (Record) -> HealthSample,
  ) : HealthReading

  /** Spot measurements Health Connect cannot aggregate: `<name>Avg<unit>`, `Min`, `Max` over the window. */
  class Stats(
    val name: String,
    val unit: String,
    val decimals: Int,
    val sample: (Record) -> HealthSample,
  ) : HealthReading {
    val avgKey get() = "${name}Avg$unit"
    val minKey get() = "${name}Min$unit"
    val maxKey get() = "${name}Max$unit"
  }
}

// Health Connect keeps its Instantaneous/Interval record interfaces internal, so each type names its time.
private inline fun <reified T : Record> latest(
  key: String,
  decimals: Int,
  crossinline sample: (T) -> Pair<Instant, Double>,
) = HealthReading.Latest(key, decimals) { record -> sample(record as T).let { (time, value) -> HealthSample(time, value, record.metadata.dataOrigin.packageName) } }

private inline fun <reified T : Record> stats(
  name: String,
  unit: String,
  decimals: Int,
  crossinline sample: (T) -> Pair<Instant, Double>,
) = HealthReading.Stats(name, unit, decimals) { record -> sample(record as T).let { (time, value) -> HealthSample(time, value, record.metadata.dataOrigin.packageName) } }

private fun <T : Any> total(
  key: String,
  decimals: Int,
  metric: AggregateMetric<T>,
  value: (T) -> Double,
) = HealthAggregateField(key, decimals, metric, value)

/** Payload fields for every type beyond the original five, in payload order. Units are in the names. */
internal val HEALTH_READINGS: Map<HealthMetric, HealthReading> =
  linkedMapOf(
    HealthMetric.TotalCalories to
      HealthReading.Totals(listOf(total("totalCaloriesKcal", 0, TotalCaloriesBurnedRecord.ENERGY_TOTAL) { it.inKilocalories })),
    HealthMetric.ActiveCalories to
      HealthReading.Totals(listOf(total("activeCaloriesKcal", 0, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { it.inKilocalories })),
    HealthMetric.Distance to HealthReading.Totals(listOf(total("distanceMeters", 0, DistanceRecord.DISTANCE_TOTAL) { it.inMeters })),
    HealthMetric.ElevationGained to
      HealthReading.Totals(listOf(total("elevationGainedMeters", 1, ElevationGainedRecord.ELEVATION_GAINED_TOTAL) { it.inMeters })),
    HealthMetric.FloorsClimbed to HealthReading.Totals(listOf(total("floorsClimbed", 1, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { it })),
    HealthMetric.WheelchairPushes to
      HealthReading.Totals(listOf(total("wheelchairPushCount", 0, WheelchairPushesRecord.COUNT_TOTAL) { it.toDouble() })),
    HealthMetric.Speed to
      HealthReading.Totals(
        listOf(
          total("speedAvgMetersPerSecond", 2, SpeedRecord.SPEED_AVG) { it.inMetersPerSecond },
          total("speedMaxMetersPerSecond", 2, SpeedRecord.SPEED_MAX) { it.inMetersPerSecond },
        ),
      ),
    HealthMetric.Power to
      HealthReading.Totals(
        listOf(
          total("powerAvgWatts", 0, PowerRecord.POWER_AVG) { it.inWatts },
          total("powerMaxWatts", 0, PowerRecord.POWER_MAX) { it.inWatts },
        ),
      ),
    HealthMetric.StepsCadence to
      HealthReading.Totals(
        listOf(
          total("stepsCadenceAvgPerMinute", 1, StepsCadenceRecord.RATE_AVG) { it },
          total("stepsCadenceMaxPerMinute", 1, StepsCadenceRecord.RATE_MAX) { it },
        ),
      ),
    HealthMetric.CyclingPedalingCadence to
      HealthReading.Totals(
        listOf(
          total("cyclingCadenceAvgRpm", 1, CyclingPedalingCadenceRecord.RPM_AVG) { it },
          total("cyclingCadenceMaxRpm", 1, CyclingPedalingCadenceRecord.RPM_MAX) { it },
        ),
      ),
    HealthMetric.Weight to latest<WeightRecord>("weightKg", 2) { it.time to it.weight.inKilograms },
    HealthMetric.Height to latest<HeightRecord>("heightMeters", 3) { it.time to it.height.inMeters },
    HealthMetric.BodyFat to latest<BodyFatRecord>("bodyFatPercent", 1) { it.time to it.percentage.value },
    HealthMetric.LeanBodyMass to latest<LeanBodyMassRecord>("leanBodyMassKg", 2) { it.time to it.mass.inKilograms },
    HealthMetric.BoneMass to latest<BoneMassRecord>("boneMassKg", 2) { it.time to it.mass.inKilograms },
    HealthMetric.BodyWaterMass to latest<BodyWaterMassRecord>("bodyWaterMassKg", 2) { it.time to it.mass.inKilograms },
    HealthMetric.BasalMetabolicRate to
      latest<BasalMetabolicRateRecord>("basalMetabolicRateKcalPerDay", 0) { it.time to it.basalMetabolicRate.inKilocaloriesPerDay },
    HealthMetric.OxygenSaturation to stats<OxygenSaturationRecord>("oxygenSaturation", "Percent", 1) { it.time to it.percentage.value },
    HealthMetric.RespiratoryRate to stats<RespiratoryRateRecord>("respiratoryRate", "PerMinute", 1) { it.time to it.rate },
    HealthMetric.HeartRateVariability to
      stats<HeartRateVariabilityRmssdRecord>("hrvRmssd", "Millis", 1) { it.time to it.heartRateVariabilityMillis },
    HealthMetric.BloodPressure to
      HealthReading.Totals(
        listOf(
          total("bloodPressureSystolicAvgMmHg", 0, BloodPressureRecord.SYSTOLIC_AVG) { it.inMillimetersOfMercury },
          total("bloodPressureSystolicMinMmHg", 0, BloodPressureRecord.SYSTOLIC_MIN) { it.inMillimetersOfMercury },
          total("bloodPressureSystolicMaxMmHg", 0, BloodPressureRecord.SYSTOLIC_MAX) { it.inMillimetersOfMercury },
          total("bloodPressureDiastolicAvgMmHg", 0, BloodPressureRecord.DIASTOLIC_AVG) { it.inMillimetersOfMercury },
          total("bloodPressureDiastolicMinMmHg", 0, BloodPressureRecord.DIASTOLIC_MIN) { it.inMillimetersOfMercury },
          total("bloodPressureDiastolicMaxMmHg", 0, BloodPressureRecord.DIASTOLIC_MAX) { it.inMillimetersOfMercury },
        ),
      ),
    HealthMetric.BloodGlucose to stats<BloodGlucoseRecord>("bloodGlucose", "MgPerDl", 0) { it.time to it.level.inMilligramsPerDeciliter },
    HealthMetric.BodyTemperature to stats<BodyTemperatureRecord>("bodyTemperature", "Celsius", 2) { it.time to it.temperature.inCelsius },
    HealthMetric.BasalBodyTemperature to
      stats<BasalBodyTemperatureRecord>("basalBodyTemperature", "Celsius", 2) { it.time to it.temperature.inCelsius },
    HealthMetric.SkinTemperature to
      HealthReading.Totals(
        listOf(
          total("skinTemperatureDeltaAvgCelsius", 2, SkinTemperatureRecord.TEMPERATURE_DELTA_AVG) { it.inCelsius },
          total("skinTemperatureDeltaMinCelsius", 2, SkinTemperatureRecord.TEMPERATURE_DELTA_MIN) { it.inCelsius },
          total("skinTemperatureDeltaMaxCelsius", 2, SkinTemperatureRecord.TEMPERATURE_DELTA_MAX) { it.inCelsius },
        ),
      ),
    HealthMetric.Vo2Max to latest<Vo2MaxRecord>("vo2MaxMlPerKgMin", 1) { it.time to it.vo2MillilitersPerMinuteKilogram },
    HealthMetric.Nutrition to
      HealthReading.Totals(
        listOf(
          total("nutritionEnergyKcal", 0, NutritionRecord.ENERGY_TOTAL) { it.inKilocalories },
          total("nutritionProteinGrams", 1, NutritionRecord.PROTEIN_TOTAL) { it.inGrams },
          total("nutritionFatGrams", 1, NutritionRecord.TOTAL_FAT_TOTAL) { it.inGrams },
          total("nutritionSaturatedFatGrams", 1, NutritionRecord.SATURATED_FAT_TOTAL) { it.inGrams },
          total("nutritionCarbohydrateGrams", 1, NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL) { it.inGrams },
          total("nutritionSugarGrams", 1, NutritionRecord.SUGAR_TOTAL) { it.inGrams },
          total("nutritionFiberGrams", 1, NutritionRecord.DIETARY_FIBER_TOTAL) { it.inGrams },
          total("nutritionSodiumMilligrams", 0, NutritionRecord.SODIUM_TOTAL) { it.inMilligrams },
          total("nutritionCaffeineMilligrams", 0, NutritionRecord.CAFFEINE_TOTAL) { it.inMilligrams },
        ),
      ),
    HealthMetric.Hydration to HealthReading.Totals(listOf(total("hydrationMilliliters", 0, HydrationRecord.VOLUME_TOTAL) { it.inMilliliters })),
  )

/** Per-workout totals, read with the workout's own time window. */
internal val HEALTH_WORKOUT_TOTAL_METRICS =
  listOf(HealthMetric.TotalCalories, HealthMetric.ActiveCalories, HealthMetric.Distance, HealthMetric.ElevationGained)

private val HEALTH_READING_DECIMALS: Map<String, Int> =
  HEALTH_READINGS.values
    .flatMap { reading ->
      when (reading) {
        is HealthReading.Totals -> reading.fields.map { it.key to it.decimals }
        is HealthReading.Latest -> listOf(reading.key to reading.decimals)
        is HealthReading.Stats -> listOf(reading.avgKey, reading.minKey, reading.maxKey).map { it to reading.decimals }
      }
    }.toMap()

/** Newest sample taken inside the window. */
internal fun latestSample(
  samples: List<HealthSample>,
  window: HealthWindow,
): HealthSample? = samples.filter { it.time >= window.start && it.time < window.end }.maxByOrNull { it.time }

internal data class HealthSampleStats(
  val avg: Double,
  val min: Double,
  val max: Double,
)

internal fun sampleStats(
  samples: List<HealthSample>,
  window: HealthWindow,
): HealthSampleStats? {
  val values = samples.filter { it.time >= window.start && it.time < window.end }.map { it.value }
  if (values.isEmpty()) return null
  return HealthSampleStats(values.average(), values.min(), values.max())
}

/** Writes a reading in its field's precision: whole numbers as integers, the rest rounded. */
internal fun JsonObjectBuilder.putReadingValue(
  key: String,
  value: Double,
) {
  if (!value.isFinite()) return
  val decimals = HEALTH_READING_DECIMALS[key] ?: 1
  if (decimals == 0) {
    put(key, value.roundToLong())
  } else {
    val scale = 10.0.pow(decimals)
    put(key, (value * scale).roundToLong() / scale)
  }
}

/** Writes the fields of every readable type in [metrics] for one window. */
internal fun JsonObjectBuilder.putReadings(
  window: HealthWindow,
  metrics: Set<HealthMetric>,
  totals: HealthTotals,
  samples: Map<HealthMetric, List<HealthSample>>,
) {
  for ((metric, reading) in HEALTH_READINGS) {
    if (metric !in metrics) continue
    when (reading) {
      is HealthReading.Totals -> {
        for (field in reading.fields) totals.values[field.key]?.let { putReadingValue(field.key, it) }
      }

      is HealthReading.Latest -> {
        samples[metric]?.let { latestSample(it, window) }?.let { putReadingValue(reading.key, it.value) }
      }

      is HealthReading.Stats -> {
        samples[metric]?.let { sampleStats(it, window) }?.let { stats ->
          putReadingValue(reading.avgKey, stats.avg)
          putReadingValue(reading.minKey, stats.min)
          putReadingValue(reading.maxKey, stats.max)
        }
      }
    }
  }
}
