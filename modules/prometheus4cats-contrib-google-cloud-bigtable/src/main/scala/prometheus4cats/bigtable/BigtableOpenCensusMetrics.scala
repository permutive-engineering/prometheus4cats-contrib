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

package prometheus4cats.bigtable

import scala.annotation.nowarn

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync

import com.google.cloud.bigtable.data.v2.BigtableDataSettings
import prometheus4cats.MetricCollection
import prometheus4cats.MetricFactory
import prometheus4cats.opencensus.OpenCensusUtils

object BigtableOpenCensusMetrics {

  private[bigtable] val openCensusPrefix = "cloud.google.com/java/bigtable/"

  private[bigtable] def metricCollection[F[_]: Sync]: F[MetricCollection] =
    OpenCensusUtils
      .openCensusAsMetricCollection[F]("bigtable_client")(
        _.getMetricDescriptor.getName
          .startsWith(openCensusPrefix),
        _.replace(openCensusPrefix, "bigtable_")
      )

  @nowarn("msg=deprecated")
  private[bigtable] def enableClientMetrics[F[_]: Sync]: F[Unit] =
    Sync[F].blocking {
      BigtableDataSettings.enableOpenCensusStats()
      BigtableDataSettings.enableGfeOpenCensusStats()
    }

  def register[F[_]: Sync](
      metricFactory: MetricFactory.WithCallbacks[F]
  ): Resource[F, Unit] =
    metricFactory.dropPrefix
      .metricCollectionCallback(metricCollection)
      .build
      .evalMap(_ => enableClientMetrics)

}
