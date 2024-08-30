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

import cats.Functor
import cats.syntax.all._

import prometheus4cats.Exemplar
import prometheus4cats.trace4cats.Trace4CatsExemplar._
import trace4cats.Trace
import trace4cats.model.SampleDecision

final class Trace4CatsExemplar[F[_]: Functor: Trace.WithContext](
    traceIdLabelName: Exemplar.LabelName,
    spanIdLabelName: Exemplar.LabelName
) extends Exemplar[F] {

  override def get: F[Option[Exemplar.Labels]] =
    Trace.WithContext[F].context.map { traceContext =>
      traceContext.traceFlags.sampled match {
        case SampleDecision.Drop => None
        case SampleDecision.Include =>
          Exemplar.Labels
            .of(
              traceIdLabelName -> traceContext.traceId.show,
              spanIdLabelName  -> traceContext.spanId.show
            )
            .toOption
      }
    }

}

object Trace4CatsExemplar extends Trace4CatsExemplarInstances {

  def apply[F[_]: Functor: Trace.WithContext](
      traceIdLabelName: Exemplar.LabelName = DefaultTraceIdLabelName,
      spanIdLabelName: Exemplar.LabelName = DefaultSpanIdLabelName
  ): Trace4CatsExemplar[F] =
    new Trace4CatsExemplar[F](traceIdLabelName, spanIdLabelName)

}

trait Trace4CatsExemplarInstances {

  implicit def trace4catsExemplarInstance[F[_]: Functor: Trace.WithContext]: Trace4CatsExemplar[F] = apply()

}
