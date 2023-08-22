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

import cats.effect.kernel.Resource
import prometheus4cats.{MetricFactory}
import trace4cats._

object InstrumentedEntrypoint {
  def create[F[_]](
      factory: MetricFactory[F],
      entryPoint: EntryPoint[F]
  ): Resource[F, EntryPoint[F]] =
    factory
      .withPrefix("trace4cats_entry_point")
      .counter("spans_total")
      .ofLong
      .help("Total number of spans created")
      .label[SpanKind]("span_kind")
      .label[Boolean]("is_root")
      .label[SampleDecision]("sample_decision")
      .contramapLabels[(SpanKind, Span[F])] { case (kind, span) =>
        (
          kind,
          span.context.parent.isEmpty,
          span.context.traceFlags.sampled
        )
      }
      .build
      .map { spanCounter =>
        new EntryPoint[F] {
          override def root(
              name: String,
              kind: SpanKind,
              errorHandler: ErrorHandler
          ): Resource[F, Span[F]] = entryPoint
            .root(name, kind, errorHandler)
            .evalTap(span => spanCounter.inc((kind, span)))

          override def continueOrElseRoot(
              name: String,
              kind: SpanKind,
              headers: TraceHeaders,
              errorHandler: ErrorHandler
          ): Resource[F, Span[F]] =
            entryPoint
              .continueOrElseRoot(name, kind, headers, errorHandler)
              .evalTap(span => spanCounter.inc((kind, span)))
        }
      }
}
