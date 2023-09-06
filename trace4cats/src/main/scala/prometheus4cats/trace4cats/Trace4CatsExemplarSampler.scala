/*
 * Copyright 2022 Permutive
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
import cats.effect.kernel.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Applicative, Monad}
import prometheus4cats.{Exemplar, ExemplarSampler}
import trace4cats.Trace
import trace4cats.model.SampleDecision

import scala.concurrent.duration._

class Trace4CatsExemplarSampler[F[_]: Monad: Clock: Trace.WithContext, A](
    minRetentionInterval: FiniteDuration,
    traceIdLabelName: Exemplar.LabelName,
    spanIdLabelName: Exemplar.LabelName
) extends ExemplarSampler[F, A] {
  private val minRetentionIntervalMs = minRetentionInterval.toMillis

  override def sample(
      previous: Option[Exemplar.Data]
  ): F[Option[Exemplar.Data]] = doSample(previous)

  override def sample(
      value: A,
      previous: Option[Exemplar.Data]
  ): F[Option[Exemplar.Data]] = doSample(previous)

  override def sample(
      value: A,
      buckets: NonEmptySeq[Double],
      previous: Option[Exemplar.Data]
  ): F[Option[Exemplar.Data]] = doSample(previous)

  private def doSample(
      previous: Option[Exemplar.Data]
  ): F[Option[Exemplar.Data]] = Clock[F].realTimeInstant.flatMap {
    currentTime =>
      val spanExemplarData = Trace.WithContext[F].context.map { traceContext =>
        traceContext.traceFlags.sampled match {
          case SampleDecision.Drop => None
          case SampleDecision.Include =>
            Exemplar.Labels
              .of(
                traceIdLabelName -> traceContext.traceId.show,
                spanIdLabelName -> traceContext.spanId.show
              )
              .toOption
              .map(labels => Exemplar.Data(labels, currentTime))
        }
      }

      previous match {
        case Some(prev) =>
          if (
            currentTime.toEpochMilli - prev.timestamp.toEpochMilli > minRetentionIntervalMs
          ) spanExemplarData
          else Applicative[F].pure(None)
        case None => spanExemplarData

      }
  }
}

object Trace4CatsExemplarSampler extends Trace4CatsExemplarSamplerInstances {
  // Choosing a prime number for the retention interval makes behavior more predictable,
  // because it is unlikely that retention happens at the exact same time as a Prometheus scrape.
  val DefaultMinRetentionInterval: FiniteDuration = 7109.millis

  def apply[F[_]: Monad: Clock: Trace.WithContext, A](
      minRetentionInterval: FiniteDuration = DefaultMinRetentionInterval,
      traceIdLabelName: Exemplar.LabelName = DefaultTraceIdLabelName,
      spanIdLabelName: Exemplar.LabelName = DefaultSpanIdLabelName
  ) = new Trace4CatsExemplarSampler[F, A](
    minRetentionInterval,
    traceIdLabelName,
    spanIdLabelName
  )
}

trait Trace4CatsExemplarSamplerInstances {
  implicit def trace4CatsExemplarSamplerInstance[F[
      _
  ]: Monad: Clock: Trace.WithContext, A]: Trace4CatsExemplarSampler[F, A] =
    Trace4CatsExemplarSampler[F, A]()
}
