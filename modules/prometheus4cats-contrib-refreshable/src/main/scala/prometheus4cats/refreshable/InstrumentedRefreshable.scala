/*
 * Copyright 2022-2025 Permutive Ltd. <https://permutive.com>
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

package prometheus4cats.refreshable

import scala.concurrent.duration.FiniteDuration

import cats.Applicative
import cats.Functor
import cats.data.NonEmptyList
import cats.effect.kernel.Async
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.MonadCancelThrow
import cats.effect.kernel.Poll
import cats.effect.kernel.Resource
import cats.effect.kernel.syntax.monadCancel._
import cats.effect.kernel.syntax.resource._
import cats.effect.kernel.syntax.spawn._
import cats.syntax.all._

import com.permutive.refreshable.CachedValue
import com.permutive.refreshable.Refreshable
import prometheus4cats._
import retry.RetryDetails

class InstrumentedRefreshable[F[_], A] private (
    underlying: Refreshable[F, A],
    name: String,
    readCounter: Counter[F, Long, (String, CachedValue[A])],
    runningGauge: Gauge[F, Boolean, String],
    exhaustedRetriesGauge: Gauge[F, Boolean, String]
)(implicit
    F: MonadCancel[F, _]
) extends Refreshable[F, A] {

  override def get: F[CachedValue[A]] = F.uncancelable { poll =>
    poll(underlying.get).flatTap(value => readCounter.inc(name -> value))
  }

  override def cancel: F[Boolean] = F.uncancelable { poll =>
    poll(underlying.cancel).flatTap(
      if (_) runningGauge.set(false, name) else Applicative[F].unit
    )

  }

  override def updates: fs2.Stream[F, CachedValue[A]] = underlying.updates

  override def restart: F[Boolean] = F.uncancelable { poll =>
    poll(underlying.restart).flatTap(
      if (_)
        runningGauge.set(true, name) >> exhaustedRetriesGauge.set(false, name)
      else Applicative[F].unit
    )

  }

}

object InstrumentedRefreshable {

  private val prefix: Metric.Prefix = "refreshable"

  private val refreshableLabelName: Label.Name = "refreshable_name"

  private def instanceMetrics[F[_]: MonadCancelThrow, A](
      metricFactory: MetricFactory[F]
  ): F[
    (
        Counter[F, Long, (String, CachedValue[A])],
        Gauge[F, Boolean, String],
        Gauge[F, Boolean, String]
    )
  ] = {
    val factory = metricFactory.withPrefix(prefix)

    (
      factory
        .counter("read_total")
        .ofLong
        .help("Number of times this Refreshable has been read")
        .label[String](refreshableLabelName)
        .label[CachedValue[A]](
          "value_state",
          {
            case CachedValue.Success(_)   => "success"
            case CachedValue.Error(_, _)  => "error"
            case CachedValue.Cancelled(_) => "cancelled"
          }
        )
        .unsafeBuild,
      factory
        .gauge("is_running")
        .ofLong
        .help("Whether this Refreshable is running or has been cancelled")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild,
      factory
        .gauge("retries_exhausted")
        .ofLong
        .help("Whether retries have been exhausted for this Refreshable")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild
    ).tupled
  }

  private def callbackMetrics[F[_]: MonadCancelThrow](
      metricFactory: MetricFactory[F]
  ): F[
    (
        Counter[F, Long, String],
        Gauge[F, Boolean, String],
        Counter[F, Long, String]
    )
  ] = {
    val factory = metricFactory.withPrefix(prefix)

    (
      factory
        .counter("refresh_success_total")
        .ofLong
        .help("Number of times refresh succeeded")
        .label[String](refreshableLabelName)
        .unsafeBuild,
      factory
        .gauge("refresh_failing")
        .ofLong
        .help("Whether refresh is currently failing")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild,
      factory
        .counter("refresh_failure_total")
        .ofLong
        .help("Number of times refresh failed")
        .label[String](refreshableLabelName)
        .unsafeBuild
    ).tupled
  }

  private def callback[F[_]: Functor, A](
      name: String,
      refreshable: Refreshable[F, A],
      metricFactory: MetricFactory.WithCallbacks[F]
  ): Resource[F, Unit] =
    metricFactory
      .withPrefix(prefix)
      .gauge("status")
      .ofLong
      .help("The current status of this Refreshable")
      .label[String](refreshableLabelName)
      .label[CachedValue[A]](
        "value_state",
        {
          case CachedValue.Success(_)   => "success"
          case CachedValue.Error(_, _)  => "error"
          case CachedValue.Cancelled(_) => "cancelled"
        }
      )
      .callback(refreshable.get.map(v => NonEmptyList.one((1L, (name, v)))))
      .build

  private def metrics[F[_]: MonadCancelThrow, A](
      name: String,
      metricFactory: MetricFactory[F]
  )(
      currentOnNewValue: Option[(A, FiniteDuration) => F[Unit]],
      currentOnRefreshFailure: PartialFunction[(Throwable, RetryDetails), F[
        Unit
      ]],
      currentOnExhaustedRetries: PartialFunction[Throwable, F[Unit]]
  ): F[
    (
        Counter[F, Long, (String, CachedValue[A])],
        Gauge[F, Boolean, String],
        Gauge[F, Boolean, String],
        (A, FiniteDuration) => F[Unit],
        PartialFunction[(Throwable, RetryDetails), F[Unit]],
        PartialFunction[Throwable, F[Unit]]
    )
  ] = {

    (instanceMetrics[F, A](metricFactory), callbackMetrics(metricFactory)).mapN {
      case (
            (readCounter, runningGauge, exhaustedRetriesGauge),
            (
              refreshSuccessCounter,
              currentlyFailingGauge,
              refreshFailureCounter
            )
          ) =>
        val onNewValue: (A, FiniteDuration) => F[Unit] =
          (a, nextRefresh) =>
            currentOnNewValue
              .fold(Applicative[F].unit)(_(a, nextRefresh))
              .guarantee(
                refreshSuccessCounter
                  .inc(name) >> currentlyFailingGauge
                  .set(false, name)
              )

        val onRefreshFailure: PartialFunction[(Throwable, RetryDetails), F[
          Unit
        ]] = { case err =>
          currentOnRefreshFailure
            .lift(err)
            .sequence_
            .guarantee(
              refreshFailureCounter
                .inc(name) >> currentlyFailingGauge
                .set(true, name)
            )
        }

        val onExhaustedRetries: PartialFunction[Throwable, F[Unit]] = { case err =>
          currentOnExhaustedRetries
            .lift(err)
            .sequence_
            .guarantee(
              exhaustedRetriesGauge.set(true, name) >> runningGauge
                .set(false, name)
            )
        }

        (
          readCounter, runningGauge, exhaustedRetriesGauge, onNewValue, onRefreshFailure, onExhaustedRetries
        )
    }
  }

  def create[F[_]: MonadCancelThrow, A](
      builder: Refreshable.RefreshableBuilder[F, A],
      name: String,
      metricFactory: MetricFactory.WithCallbacks[F]
  ): Resource[F, InstrumentedRefreshable[F, A]] =
    metrics(name, metricFactory)(
      builder.newValueCallback,
      builder.refreshFailureCallback,
      builder.exhaustedRetriesCallback
    ).toResource.flatMap {
      case (
            readCounter, runningGauge, exhaustedRetriesGauge, onNewValue, onRefreshFailure, onExhaustedRetries
          ) =>
        builder
          .onNewValue(onNewValue)
          .onRefreshFailure(onRefreshFailure)
          .onExhaustedRetries(onExhaustedRetries)
          .resource
          .evalTap(_ => runningGauge.set(true, name))
          .flatTap(callback(name, _, metricFactory))
          .map { refreshable =>
            new InstrumentedRefreshable[F, A](
              refreshable, name, readCounter, runningGauge, exhaustedRetriesGauge
            )
          }
    }

  def fromExisting[F[_]: Async, A](
      refreshable: Refreshable[F, A],
      name: String,
      metricFactory: MetricFactory.WithCallbacks[F]
  ): Resource[F, InstrumentedRefreshable[F, A]] =
    (
      instanceMetrics[F, A](metricFactory).toResource,
      callbackMetrics(metricFactory).toResource
    ).flatMapN {
      case (
            (readCounter, runningGauge, exhaustedRetriesGauge),
            (
              refreshSuccessCounter,
              currentlyFailingGauge,
              refreshFailureCounter
            )
          ) =>
        callback(name, refreshable, metricFactory) >> Resource
          .uncancelable((_: Poll[Resource[F, *]]) =>
            Resource.make(runningGauge.set(true, name))(_ =>
              runningGauge.set(false, name)
            ) >> refreshable.updates.evalMap {
              case CachedValue.Success(_) =>
                refreshSuccessCounter
                  .inc(name) >> currentlyFailingGauge.set(
                  false,
                  name
                ) >> runningGauge.set(true, name)
              case CachedValue.Error(_, _) =>
                refreshFailureCounter
                  .inc(name) >> currentlyFailingGauge.set(
                  true,
                  name
                ) >> runningGauge.set(true, name)
              case CachedValue.Cancelled(_) => runningGauge.set(false, name)
            }.compile.drain.background
          )
          .as(
            new InstrumentedRefreshable[F, A](
              refreshable, name, readCounter, runningGauge, exhaustedRetriesGauge
            )
          )
    }

}
