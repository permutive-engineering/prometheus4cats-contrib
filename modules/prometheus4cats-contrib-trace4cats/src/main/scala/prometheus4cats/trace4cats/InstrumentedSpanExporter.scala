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

package prometheus4cats.trace4cats

import cats.UnorderedFoldable
import cats.data.NonEmptySeq
import cats.effect.MonadCancelThrow
import cats.effect.kernel.Clock
import cats.effect.kernel.Resource
import cats.syntax.all._

import _root_.trace4cats.kernel.SpanExporter
import prometheus4cats.Label
import prometheus4cats.MetricFactory
import trace4cats.model.Batch

object InstrumentedSpanExporter {

  val DefaultTimerHistogramBuckets: NonEmptySeq[Double] =
    NonEmptySeq
      .of(1, 5, 10, 50, 100, 200, 500, 1000, 5000, 10000)
      .map(_ / 1000d)

  val DefaultBatchSizeHistogramBuckets: NonEmptySeq[Long] =
    NonEmptySeq.of(1, 5, 10, 50, 100, 200, 1000, 2000)

  private val exporterNameLabel: Label.Name = "exporter_name"

  def create[F[_]: MonadCancelThrow: Clock, G[_]: UnorderedFoldable](
      factory: MetricFactory[F],
      exporter: SpanExporter[F, G],
      exporterName: String,
      timerHistogramBuckets: NonEmptySeq[Double] = DefaultTimerHistogramBuckets,
      batchSizeHistogramBuckets: NonEmptySeq[Long] = DefaultBatchSizeHistogramBuckets
  ): Resource[F, SpanExporter[F, G]] = {
    val metricFactory = factory.withPrefix("trace4cats_exporter")

    for {
      outcomeRecorder <- metricFactory
                           .counter("batches_total")
                           .ofLong
                           .help("Total number of batches sent via this exporter")
                           .label[String](exporterNameLabel)
                           .asOutcomeRecorder
                           .build
      timer <- metricFactory
                 .histogram("export_time")
                 .ofDouble
                 .help("Time it takes to export a span batch in seconds")
                 .buckets(timerHistogramBuckets)
                 .label[String](exporterNameLabel)
                 .asTimer
                 .build
      batchSize <- metricFactory
                     .histogram("batch_size")
                     .ofLong
                     .help("Size distribution of batches sent by this exporter")
                     .buckets(batchSizeHistogramBuckets)
                     .label[String](exporterNameLabel)
                     .build
    } yield new SpanExporter[F, G] {
      override def exportBatch(batch: Batch[G]): F[Unit] = timer.time(
        outcomeRecorder.surround(exporter.exportBatch(batch), exporterName),
        exporterName
      ) >> batchSize.observe(batch.spans.size, exporterName)
    }
  }

}
