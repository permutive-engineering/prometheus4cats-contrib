package prometheus4cats.bigtable

import cats.effect.kernel.{Resource, Sync}
import com.google.cloud.bigtable.data.v2.BigtableDataSettings
import prometheus4cats.{MetricCollection, MetricFactory}
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
