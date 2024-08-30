/*
 * Copyright 2022-2024 Permutive Ltd. <https://permutive.com>
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

import cats.data.NonEmptySeq
import cats.effect.MonadCancelThrow
import cats.effect.kernel.Clock
import cats.effect.kernel.Resource

import _root_.trace4cats.kernel.SpanCompleter
import prometheus4cats.Label
import prometheus4cats.MetricFactory
import trace4cats.model.CompletedSpan

object InstrumentedSpanCompleter {

  val DefaultTimerHistogramBuckets: NonEmptySeq[Double] =
    NonEmptySeq.of(0.1, 0.5, 1, 5, 10, 20).map(_ / 1000d)

  private val completerNameLabel: Label.Name = "completer_name"

  def create[F[_]: MonadCancelThrow: Clock](
      factory: MetricFactory[F],
      completer: SpanCompleter[F],
      completerName: String,
      timerHistogramBuckets: NonEmptySeq[Double] = DefaultTimerHistogramBuckets
  ): Resource[F, SpanCompleter[F]] = {
    val metricFactory = factory.withPrefix("trace4cats_completer")

    for {
      outcomeRecorder <- metricFactory
                           .counter("spans_total")
                           .ofLong
                           .help("Total number of spans completed")
                           .label[String](completerNameLabel)
                           .asOutcomeRecorder
                           .build
      timer <- metricFactory
                 .histogram("complete_time")
                 .ofDouble
                 .help("Time it takes to complete a span in seconds")
                 .buckets(timerHistogramBuckets)
                 .label[String](completerNameLabel)
                 .asTimer
                 .build
    } yield new SpanCompleter[F] {
      override def complete(span: CompletedSpan.Builder): F[Unit] =
        timer.time(
          outcomeRecorder.surround(completer.complete(span), completerName),
          completerName
        )
    }
  }

}
