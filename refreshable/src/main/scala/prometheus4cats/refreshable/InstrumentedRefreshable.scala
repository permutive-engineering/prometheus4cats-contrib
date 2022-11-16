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

package prometheus4cats.refreshable

import cats.Applicative
import cats.effect.kernel.syntax.monadCancel._
import cats.effect.kernel.syntax.resource._
import cats.effect.kernel.{MonadCancel, MonadCancelThrow, Resource}
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import com.permutive.refreshable.{CachedValue, Refreshable}
import prometheus4cats._
import retry.RetryDetails

import scala.concurrent.duration.FiniteDuration

class InstrumentedRefreshable[F[_], A] private (
    underlying: Refreshable[F, A],
    name: String,
    readCounter: Counter.Labelled[F, Long, (String, CachedValue[A])],
    runningGauge: Gauge.Labelled[F, Boolean, String],
    exhaustedRetriesGauge: Gauge.Labelled[F, Boolean, String]
)(implicit
    override val functor: MonadCancel[F, _]
) extends Refreshable[F, A] {
  override def get: F[CachedValue[A]] = functor.uncancelable { poll =>
    poll(underlying.get.flatTap(value => readCounter.inc(name -> value)))
  }

  override def cancel: F[Boolean] = functor.uncancelable { poll =>
    poll(
      underlying.cancel.flatTap(
        if (_) runningGauge.set(false, name) else Applicative[F].unit
      )
    )
  }

  override def restart: F[Boolean] = functor.uncancelable { poll =>
    poll(
      underlying.restart.flatTap(
        if (_)
          runningGauge.set(true, name) >> exhaustedRetriesGauge.set(false, name)
        else Applicative[F].unit
      )
    )
  }
}

object InstrumentedRefreshable {
  private val prefix: Metric.Prefix = "refreshable"
  private val refreshableLabelName: Label.Name = "refreshable_name"

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
        Counter.Labelled[F, Long, (String, CachedValue[A])],
        Gauge.Labelled[F, Boolean, String],
        Gauge.Labelled[F, Boolean, String],
        (A, FiniteDuration) => F[Unit],
        PartialFunction[(Throwable, RetryDetails), F[Unit]],
        PartialFunction[Throwable, F[Unit]]
    )
  ] = {
    val factory = metricFactory.withPrefix(prefix)

    for {
      refreshSuccessCounter <- factory
        .counter("refresh_success_total")
        .ofLong
        .help("Number of times refresh succeeded")
        .label[String](refreshableLabelName)
        .unsafeBuild

      currentlyFailingGauge <- factory
        .gauge("refresh_failing")
        .ofLong
        .help("Whether refresh is currently failing")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild

      refreshFailureCounter <- factory
        .counter("refresh_failure_total")
        .ofLong
        .help("Number of times refresh failed")
        .label[String](refreshableLabelName)
        .unsafeBuild

      exhaustedRetriesGauge <- factory
        .gauge("retries_exhausted")
        .ofLong
        .help("Whether retries have been exhausted for this Refreshable")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild

      readCounter <- factory
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
        .unsafeBuild

      runningGauge <- factory
        .gauge("is_running")
        .ofLong
        .help("Whether this Refreshable is running or has been cancelled")
        .label[String](refreshableLabelName)
        .contramap[Boolean](if (_) 1L else 0L)
        .unsafeBuild

    } yield {
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

      val onExhaustedRetries: PartialFunction[Throwable, F[Unit]] = {
        case err =>
          currentOnExhaustedRetries
            .lift(err)
            .sequence_
            .guarantee(
              exhaustedRetriesGauge.set(true, name) >> runningGauge
                .set(false, name)
            )
      }

      (
        readCounter,
        runningGauge,
        exhaustedRetriesGauge,
        onNewValue,
        onRefreshFailure,
        onExhaustedRetries
      )
    }
  }

  class Updates[F[_], A](
      underlying: Refreshable.Updates[F, A],
      name: String,
      readCounter: Counter.Labelled[F, Long, (String, CachedValue[A])],
      runningGauge: Gauge.Labelled[F, Boolean, String],
      exhaustedRetriesGauge: Gauge.Labelled[F, Boolean, String]
  )(implicit
      override val functor: MonadCancel[F, _]
  ) extends InstrumentedRefreshable[F, A](
        underlying,
        name,
        readCounter,
        runningGauge,
        exhaustedRetriesGauge
      )
      with Refreshable.Updates[F, A] {
    override def updates: fs2.Stream[F, CachedValue[A]] = underlying.updates
  }

  object Updates {
    def create[F[_]: MonadCancelThrow, A](
        builder: Refreshable.UpdatesBuilder[F, A],
        name: String,
        metricFactory: MetricFactory[F]
    ): Resource[F, Updates[F, A]] = metrics(name, metricFactory)(
      builder.newValueCallback,
      builder.refreshFailureCallback,
      builder.exhaustedRetriesCallback
    ).toResource.flatMap {
      case (
            readCounter,
            runningGauge,
            exhaustedRetriesGauge,
            onNewValue,
            onRefreshFailure,
            onExhaustedRetries
          ) =>
        builder
          .onNewValue(onNewValue)
          .onRefreshFailure(onRefreshFailure)
          .onExhaustedRetries(onExhaustedRetries)
          .resource
          .evalTap(_ => runningGauge.set(true, name))
          .map { refreshable =>
            new InstrumentedRefreshable.Updates[F, A](
              refreshable,
              name,
              readCounter,
              runningGauge,
              exhaustedRetriesGauge
            )
          }
    }
  }

  def create[F[_]: MonadCancelThrow, A](
      builder: Refreshable.RefreshableBuilder[F, A],
      name: String,
      metricFactory: MetricFactory[F]
  ): Resource[F, InstrumentedRefreshable[F, A]] =
    metrics(name, metricFactory)(
      builder.newValueCallback,
      builder.refreshFailureCallback,
      builder.exhaustedRetriesCallback
    ).toResource.flatMap {
      case (
            readCounter,
            runningGauge,
            exhaustedRetriesGauge,
            onNewValue,
            onRefreshFailure,
            onExhaustedRetries
          ) =>
        builder
          .onNewValue(onNewValue)
          .onRefreshFailure(onRefreshFailure)
          .onExhaustedRetries(onExhaustedRetries)
          .resource
          .evalTap(_ => runningGauge.set(true, name))
          .map { refreshable =>
            new InstrumentedRefreshable[F, A](
              refreshable,
              name,
              readCounter,
              runningGauge,
              exhaustedRetriesGauge
            )
          }
    }

}
