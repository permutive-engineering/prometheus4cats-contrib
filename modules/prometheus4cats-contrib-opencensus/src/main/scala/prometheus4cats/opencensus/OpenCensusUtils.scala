/*
 * Copyright 2022-2026 Permutive Ltd. <https://permutive.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package prometheus4cats.opencensus

import scala.jdk.CollectionConverters._

import cats.data.NonEmptySeq
import cats.effect.kernel.Sync
import cats.syntax.all._

import io.opencensus.metrics.Metrics
import io.opencensus.metrics.`export`.Distribution
import io.opencensus.metrics.`export`.Metric
import io.opencensus.metrics.`export`.MetricDescriptor
import io.opencensus.metrics.`export`.Summary
import prometheus4cats._

// Derived from https://github.com/census-instrumentation/opencensus-java/blob/master/exporters/stats/prometheus/src/main/java/io/opencensus/exporter/stats/prometheus/PrometheusExportUtils.java#L82

object OpenCensusUtils {

  private val parseErrorsGaugeName: Gauge.Name =
    "prometheus4cats_opencensus_parse_errors"

  private val parseErrorsExporterLabel: Label.Name = "exporter"

  private def convertMetric(
      col: MetricCollection,
      metric: Metric
  )(formatName: String => String): Either[String, MetricCollection] = {
    lazy val name = formatName(metric.getMetricDescriptor.getName)

    def parseHelpAndLabels = for {
      help <- prometheus4cats.Metric.Help.from(
                metric.getMetricDescriptor.getDescription
              )
      labelNames <- metric.getMetricDescriptor.getLabelKeys.asScala.toList
                      .traverse(lk => Label.Name.from(lk.getKey))
    } yield (help, labelNames.toIndexedSeq)

    def getValues[A](
        onDouble: Double => Option[A] = (_: Double) => None,
        onLong: Long => Option[A] = (_: Long) => None,
        onDistribution: Distribution => Option[A] = (_: Distribution) => None,
        onSummary: Summary => Option[A] = (_: Summary) => None
    ): List[(A, IndexedSeq[String])] =
      metric.getTimeSeriesList.asScala.toList.flatMap { ts =>
        ts.getPoints.asScala.flatMap { point =>
          point.getValue
            .`match`[Option[A]](
              double => onDouble(double),
              long => onLong(long),
              dist => onDistribution(dist),
              summ => onSummary(summ),
              _ => None
            )
            .tupleRight(
              ts.getLabelValues.asScala.toIndexedSeq.map(_.getValue)
            )
        }
      }

    metric.getMetricDescriptor.getType match {
      case MetricDescriptor.Type.CUMULATIVE_INT64 =>
        for {
          name          <- Counter.Name.from(s"${name}_total")
          helpAndLabels <- parseHelpAndLabels
        } yield col.appendLongCounter(
          name,
          helpAndLabels._1,
          helpAndLabels._2,
          getValues(onLong = Some(_))
        )
      case MetricDescriptor.Type.CUMULATIVE_DOUBLE =>
        for {
          name          <- Counter.Name.from(s"${name}_total")
          helpAndLabels <- parseHelpAndLabels
        } yield col.appendDoubleCounter(
          name,
          helpAndLabels._1,
          helpAndLabels._2,
          getValues(onDouble = Some(_))
        )
      case MetricDescriptor.Type.GAUGE_INT64 =>
        for {
          name          <- Gauge.Name.from(name)
          helpAndLabels <- parseHelpAndLabels
        } yield col.appendLongGauge(
          name,
          helpAndLabels._1,
          helpAndLabels._2,
          getValues(onLong = Some(_))
        )
      case MetricDescriptor.Type.GAUGE_DOUBLE =>
        for {
          name          <- Gauge.Name.from(name)
          helpAndLabels <- parseHelpAndLabels
        } yield col.appendDoubleGauge(
          name,
          helpAndLabels._1,
          helpAndLabels._2,
          getValues(onDouble = Some(_))
        )
      case MetricDescriptor.Type.CUMULATIVE_DISTRIBUTION | MetricDescriptor.Type.GAUGE_DISTRIBUTION =>
        for {
          name          <- Histogram.Name.from(name)
          helpAndLabels <- parseHelpAndLabels
        } yield {
          val values = getValues(onDistribution = { distribution =>
            Option(distribution.getBucketOptions)
              .flatMap(
                _.`match`[Option[NonEmptySeq[Double]]](
                  explicit =>
                    NonEmptySeq.fromSeq(
                      explicit.getBucketBoundaries.asScala
                        .map(_.toDouble)
                        .toList
                    ),
                  _ => None // we only support pre-defined buckets
                )
              )
              .flatMap { boundaries =>
                NonEmptySeq
                  .fromSeq(
                    distribution.getBuckets.asScala
                      .map(_.getCount.toDouble)
                      .toList
                  )
                  .map { bucketValues =>
                    (
                      boundaries,
                      Histogram.Value(distribution.getSum, bucketValues)
                    )
                  }

              }
          })

          values.map(_._1._1).headOption.fold(col) { buckets =>
            col.appendDoubleHistogram(
              name,
              helpAndLabels._1,
              helpAndLabels._2,
              buckets,
              values.map { case ((_, v), ls) => v -> ls }
            )
          }
        }
      case MetricDescriptor.Type.SUMMARY =>
        for {
          name          <- prometheus4cats.Summary.Name.from(name)
          helpAndLabels <- parseHelpAndLabels
        } yield col.appendDoubleSummary(
          name,
          helpAndLabels._1,
          helpAndLabels._2,
          getValues[prometheus4cats.Summary.Value[Double]](onSummary = { summary =>
            Some(
              prometheus4cats.Summary
                .Value(
                  summary.getCount.toDouble,
                  summary.getSum,
                  summary.getSnapshot.getValueAtPercentiles.asScala.map { valueAtPercentile =>
                    valueAtPercentile.getPercentile -> valueAtPercentile.getValue
                  }.toMap
                )
            )
          })
        )
    }
  }

  def openCensusAsMetricCollection[F[_]: Sync](name: String)(
      metricFilter: Metric => Boolean,
      formatName: String => String
  ): F[MetricCollection] = Sync[F]
    .blocking(
      Metrics.getExportComponent.getMetricProducerManager.getAllMetricProducer.asScala.toList
        .flatMap(_.getMetrics.asScala)
    )
    .map { metrics =>
      val (col, parseErrors) = metrics.foldLeft((MetricCollection.empty, 0)) {
        case ((col, parseErrors), metric) if metricFilter(metric) =>
          convertMetric(col, metric)(formatName)
            .fold(_ => (col, parseErrors + 1), (_, parseErrors))
        case (acc, _) => acc
      }

      col.appendLongGauge(
        parseErrorsGaugeName,
        "Errors encountered when parsing Open Census metrics",
        Map(parseErrorsExporterLabel -> name),
        parseErrors.toLong
      )
    }

}
