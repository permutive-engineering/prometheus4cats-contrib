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

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import com.permutive.refreshable.Refreshable
import prometheus4cats.MetricFactory

private[refreshable] trait RefreshableBuilderSyntax {
  implicit class InstrumentedRefreshableBuilder[F[_], A](
      builder: Refreshable.RefreshableBuilder[F, A]
  ) {
    def instrumentedResource(name: String, metricFactory: MetricFactory[F])(
        implicit F: MonadCancelThrow[F]
    ): Resource[F, InstrumentedRefreshable[F, A]] =
      InstrumentedRefreshable.create(builder, name, metricFactory)
  }
}
