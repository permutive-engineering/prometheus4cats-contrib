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

package prometheus4cats.contrib

import scala.jdk.CollectionConverters._

import cats.data.NonEmptySeq
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.std.Dispatcher

import io.prometheus.metrics.model.registry.MultiCollector
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.Exemplars
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.metrics.model.snapshots.Labels
import io.prometheus.metrics.model.snapshots.MetricMetadata
import io.prometheus.metrics.model.snapshots.MetricSnapshot
import io.prometheus.metrics.model.snapshots.MetricSnapshots
import io.prometheus.metrics.model.snapshots.{Exemplar => PExemplar}
import io.prometheus.metrics.model.snapshots.{Quantile => PQuantile}
import io.prometheus.metrics.model.snapshots.{Quantiles => PQuantiles}
import io.prometheus.metrics.model.snapshots.SummarySnapshot
import prometheus4cats.Label
import prometheus4cats.Metric
import prometheus4cats.MetricCollection
import prometheus4cats.util.NameUtils

/** Adapter that exposes a `prometheus4cats.MetricCollection`-producing effect as an upstream
  * `io.prometheus.metrics.model.registry.MultiCollector`. The collector runs the effect synchronously via a
  * `Dispatcher` on each scrape. This replaces the old `MetricFactory.metricCollectionCallback` machinery, which was
  * removed from prometheus4cats upstream.
  */
object MetricCollectionCollector {

  /** Build a collector that runs `collection` on each scrape and register it against `registry`. Unregisters on
    * release.
    */
  def register[F[_]: Async](
      registry: PrometheusRegistry,
      prefix: Option[Metric.Prefix],
      commonLabels: Map[Label.Name, String],
      collection: F[MetricCollection]
  ): Resource[F, Unit] =
    Dispatcher.parallel[F].flatMap { dispatcher =>
      val collector = build(dispatcher, prefix, commonLabels, collection)
      Resource.make(Async[F].delay(registry.register(collector)))(_ =>
        Async[F].delay(registry.unregister(collector))
      )
    }

  private[contrib] def build[F[_]](
      dispatcher: Dispatcher[F],
      prefix: Option[Metric.Prefix],
      commonLabels: Map[Label.Name, String],
      collection: F[MetricCollection]
  ): MultiCollector =
    new MultiCollector {
      override def collect(): MetricSnapshots = {
        val mc        = dispatcher.unsafeRunSync(collection)
        val snapshots = metricCollectionToSnapshots(prefix, commonLabels, mc)
        new MetricSnapshots(snapshots.asJava)
      }
    }

  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  private def metricCollectionToSnapshots(
      prefix: Option[Metric.Prefix],
      commonLabels: Map[Label.Name, String],
      mc: MetricCollection
  ): List[MetricSnapshot] = {
    val commonLabelKeysArr   = commonLabels.keys.toArray.map(_.value)
    val commonLabelValuesArr = commonLabels.values.toArray

    def labelsFor(labelNames: IndexedSeq[Label.Name], labelValues: IndexedSeq[String]): Labels = {
      val nameArr  = labelNames.map(_.value).toArray ++ commonLabelKeysArr
      val valueArr = labelValues.toArray ++ commonLabelValuesArr
      Labels.of(nameArr, valueArr)
    }

    val counterSnapshots: List[MetricSnapshot] = mc.counters.toList.flatMap { case ((name, labelNames), values) =>
      values.headOption.map { head =>
        val rawName  = NameUtils.makeName(prefix, name)
        val baseName = if (rawName.endsWith("_total")) rawName.dropRight("_total".length) else rawName
        val dps = values.map { v =>
          val (vDouble, lbls) = v match {
            case x: MetricCollection.Value.LongCounter   => (x.value.toDouble, x.labelValues)
            case x: MetricCollection.Value.DoubleCounter => (x.value, x.labelValues)
          }
          new CounterSnapshot.CounterDataPointSnapshot(
            if (vDouble < 0) 0.0 else vDouble,
            labelsFor(labelNames, lbls),
            null: PExemplar,
            0L
          )
        }.asJava
        new CounterSnapshot(new MetricMetadata(baseName, head.help.value), dps): MetricSnapshot
      }
    }

    val gaugeSnapshots: List[MetricSnapshot] = mc.gauges.toList.flatMap { case ((name, labelNames), values) =>
      values.headOption.map { head =>
        val fullName = NameUtils.makeName(prefix, name)
        val dps = values.map { v =>
          val (vDouble, lbls) = v match {
            case x: MetricCollection.Value.LongGauge   => (x.value.toDouble, x.labelValues)
            case x: MetricCollection.Value.DoubleGauge => (x.value, x.labelValues)
          }
          new GaugeSnapshot.GaugeDataPointSnapshot(vDouble, labelsFor(labelNames, lbls), null: PExemplar)
        }.asJava
        new GaugeSnapshot(new MetricMetadata(fullName, head.help.value), dps): MetricSnapshot
      }
    }

    val histogramSnapshots: List[MetricSnapshot] = mc.histograms.toList.flatMap { case ((name, labelNames), values) =>
      values.headOption.map { head =>
        val fullName = NameUtils.makeName(prefix, name)
        val buckets: NonEmptySeq[Double] = head match {
          case h: MetricCollection.Value.LongHistogram   => h.buckets.map(_.toDouble)
          case h: MetricCollection.Value.DoubleHistogram => h.buckets
        }
        val upperBoundsWithInf = (buckets.toSeq :+ Double.PositiveInfinity).toArray
        val dps = values.map { v =>
          val (sum, cumulativeCounts, lbls) = v match {
            case x: MetricCollection.Value.LongHistogram =>
              (x.value.sum.toDouble, x.value.bucketValues.toSeq.map(_.toLong), x.labelValues)
            case x: MetricCollection.Value.DoubleHistogram =>
              (x.value.sum, x.value.bucketValues.toSeq.map(_.toLong), x.labelValues)
          }
          val perBucket =
            (cumulativeCounts.head +: cumulativeCounts.zip(cumulativeCounts.tail).map { case (prev, curr) =>
              curr - prev
            }).toArray
          new HistogramSnapshot.HistogramDataPointSnapshot(
            ClassicHistogramBuckets.of(upperBoundsWithInf, perBucket),
            sum,
            labelsFor(labelNames, lbls),
            Exemplars.EMPTY,
            0L
          )
        }.asJava
        new HistogramSnapshot(new MetricMetadata(fullName, head.help.value), dps): MetricSnapshot
      }
    }

    val summarySnapshots: List[MetricSnapshot] = mc.summaries.toList.flatMap { case ((name, labelNames), values) =>
      values.headOption.map { head =>
        val fullName = NameUtils.makeName(prefix, name)
        val dps = values.map { v =>
          val (count, sum, quantiles, lbls) = v match {
            case x: MetricCollection.Value.LongSummary =>
              (
                x.value.count.toLong,
                x.value.sum.toDouble,
                x.value.quantiles.map { case (q, v) => q -> v.toDouble },
                x.labelValues
              )
            case x: MetricCollection.Value.DoubleSummary =>
              (x.value.count.toLong, x.value.sum, x.value.quantiles, x.labelValues)
          }
          val quantilesJava = quantiles.toList.map { case (q, v) => new PQuantile(q, v) }.toArray
          val pquantiles =
            if (quantilesJava.isEmpty) PQuantiles.EMPTY else PQuantiles.of(quantilesJava: _*)
          new SummarySnapshot.SummaryDataPointSnapshot(
            count,
            sum,
            pquantiles,
            labelsFor(labelNames, lbls),
            Exemplars.EMPTY,
            0L
          )
        }.asJava
        new SummarySnapshot(new MetricMetadata(fullName, head.help.value), dps): MetricSnapshot
      }
    }

    counterSnapshots ::: gaugeSnapshots ::: histogramSnapshots ::: summarySnapshots
  }

}
