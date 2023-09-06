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

import cats.FlatMap
import cats.data.NonEmptySeq
import cats.effect.kernel.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import prometheus4cats.{Exemplar, ExemplarSampler}
import trace4cats.Trace
import trace4cats.model.SampleDecision

import scala.concurrent.duration._

abstract class Trace4CatsExemplarSampler[F[
    _
]: FlatMap: Clock: Trace.WithContext, A](
    traceIdLabelName: Exemplar.LabelName,
    spanIdLabelName: Exemplar.LabelName
) { self: ExemplarSampler[F, A] =>
  protected val spanExemplarData: F[Option[Exemplar.Data]] =
    Clock[F].realTimeInstant.flatMap { currentTime =>
      Trace.WithContext[F].context.map { traceContext =>
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
    }
}

object Trace4CatsExemplarSampler extends Trace4CatsExemplarSamplerInstances {
  abstract class FromTraceContext[F[_]: FlatMap: Clock: Trace.WithContext, A](
      traceIdLabelName: Exemplar.LabelName,
      spanIdLabelName: Exemplar.LabelName
  ) extends Trace4CatsExemplarSampler[F, A](traceIdLabelName, spanIdLabelName)
      with ExemplarSampler[F, A] {

    protected def sampleImpl(
        value: A,
        buckets: NonEmptySeq[Double],
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data]

    protected def sampleImpl(
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data]

    protected def sampleImpl(
        value: A,
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data]

    override def sample(
        value: A,
        buckets: NonEmptySeq[Double],
        previous: Option[Exemplar.Data]
    ): F[Option[Exemplar.Data]] =
      spanExemplarData.map(sampleImpl(value, buckets, previous, _))

    override def sample(
        previous: Option[Exemplar.Data]
    ): F[Option[Exemplar.Data]] = spanExemplarData.map(sampleImpl(previous, _))

    override def sample(
        value: A,
        previous: Option[Exemplar.Data]
    ): F[Option[Exemplar.Data]] =
      spanExemplarData.map(sampleImpl(value, previous, _))
  }

  abstract class Simple[F[_]: FlatMap: Clock: Trace.WithContext, A](
      traceIdLabelName: Exemplar.LabelName,
      spanIdLabelName: Exemplar.LabelName
  ) extends FromTraceContext[F, A](traceIdLabelName, spanIdLabelName) {
    override protected def sampleImpl(
        value: A,
        buckets: NonEmptySeq[Double],
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data] = sampleImpl(previous, next)

    override protected def sampleImpl(
        value: A,
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data] = sampleImpl(previous, next)
  }

  class Default[
      F[_]: FlatMap: Clock: Trace.WithContext,
      A
  ](
      minRetentionInterval: FiniteDuration,
      traceIdLabelName: Exemplar.LabelName,
      spanIdLabelName: Exemplar.LabelName
  ) extends Simple[F, A](traceIdLabelName, spanIdLabelName) {
    private val minRetentionIntervalMs = minRetentionInterval.toMillis

    override protected def sampleImpl(
        previous: Option[Exemplar.Data],
        next: Option[Exemplar.Data]
    ): Option[Exemplar.Data] = previous match {
      case Some(prev) =>
        next
          .filter(
            _.timestamp.toEpochMilli - prev.timestamp.toEpochMilli > minRetentionIntervalMs
          )
          .orElse(previous)
      case None => next
    }
  }

  // Choosing a prime number for the retention interval makes behavior more predictable,
  // because it is unlikely that retention happens at the exact same time as a Prometheus scrape.
  val DefaultMinRetentionInterval: FiniteDuration = 7109.millis

  def default[F[_]: FlatMap: Clock: Trace.WithContext, A](
      minRetentionInterval: FiniteDuration = DefaultMinRetentionInterval,
      traceIdLabelName: Exemplar.LabelName = DefaultTraceIdLabelName,
      spanIdLabelName: Exemplar.LabelName = DefaultSpanIdLabelName
  ): Default[F, A] =
    new Default[F, A](
      minRetentionInterval,
      traceIdLabelName,
      spanIdLabelName
    )
}

trait Trace4CatsExemplarSamplerInstances {
  implicit def trace4CatsExemplarSamplerInstance[F[
      _
  ]: FlatMap: Clock: Trace.WithContext, A]: Trace4CatsExemplarSampler[F, A] =
    Trace4CatsExemplarSampler.default[F, A]()
}
