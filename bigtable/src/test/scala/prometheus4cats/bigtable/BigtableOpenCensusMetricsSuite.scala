package prometheus4cats.bigtable

import cats.data.NonEmptySeq
import cats.effect.syntax.resource._
import cats.effect.{IO, Resource}
import cats.syntax.flatMap._
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest
import com.google.cloud.bigtable.admin.v2.{
  BigtableTableAdminClient,
  BigtableTableAdminSettings
}
import com.google.cloud.bigtable.data.v2.models.RowMutation
import com.google.cloud.bigtable.data.v2.{
  BigtableDataClient,
  BigtableDataSettings
}
import com.google.cloud.bigtable.emulator.v2.Emulator
import io.opencensus.metrics.Metrics
import munit.CatsEffectSuite
import prometheus4cats.MetricCollection.Value
import prometheus4cats.MetricCollection.Value.LongGauge
import prometheus4cats.{Label, MetricCollection}

import scala.jdk.CollectionConverters._

class BigtableOpenCensusMetricsSuite extends CatsEffectSuite {

  val setupInstance = BigtableOpenCensusMetrics
    .enableClientMetrics[IO]
    .toResource >> IO(Emulator.createBundled()).toResource
    .flatTap(e => Resource.make(IO(e.start()))(_ => IO(e.stop())))
    .evalMap { bigtableEmulator =>
      val dataSettings = BigtableDataSettings
        .newBuilderForEmulator(bigtableEmulator.getPort)
        .setProjectId("test")
        .setInstanceId("test")

      val tableAdminSettings = BigtableTableAdminSettings
        .newBuilderForEmulator(bigtableEmulator.getPort)
        .setProjectId("test")
        .setInstanceId("test")

      val tableAdminClient =
        BigtableTableAdminClient.create(tableAdminSettings.build())

      val dataClient = BigtableDataClient.create(dataSettings.build())

      IO(
        tableAdminClient.createTable(
          CreateTableRequest
            .of("test-table")
            .addFamily("cf")
        )
      ) >> IO(
        dataClient.mutateRow(
          RowMutation
            .create("test-table", "test-key")
            .setCell("cf", "col", "value")
        )
      )
    }

  test("label names and values are the same size") {

    def testLabels[A](
        map: Map[(A, IndexedSeq[Label.Name]), List[MetricCollection.Value]]
    ) =
      map.foreach { case ((name, labelNames), values) =>
        values.foreach(v =>
          assertEquals(
            labelNames.size,
            v.labelValues.size,
            s"Label names and values are not the same size for $name"
          )
        )
      }

    setupInstance.surround(
      BigtableOpenCensusMetrics.metricCollection[IO].map { collection =>
        testLabels(collection.gauges)
        testLabels(collection.counters)
        testLabels(collection.histograms)
        testLabels(collection.summaries)
      }
    )

  }

  test("histogram buckets are expected size") {

    setupInstance.surround(
      BigtableOpenCensusMetrics
        .metricCollection[IO]
        .map(_.histograms.foreach { case ((name, _), values) =>
          def test[A](buckets: NonEmptySeq[A], bucketValues: NonEmptySeq[A]) =
            assertEquals(
              buckets.length + 1,
              bucketValues.length,
              s"Incorrect bucket values length for histogram ${name.value}. " +
                "The number of bucket values must always be 1 greater than number of defined buckets"
            )

          values.foreach {
            case Value.LongHistogram(buckets, _, _, value) =>
              test(buckets, value.bucketValues)
            case Value.DoubleHistogram(buckets, _, _, value) =>
              test(buckets, value.bucketValues)
          }

        })
    )

  }

  test("exports expected metrics") {
    setupInstance.surround(
      IO.blocking(
        Metrics.getExportComponent.getMetricProducerManager.getAllMetricProducer.asScala.toList
          .flatMap(_.getMetrics.asScala)
          .filter(
            _.getMetricDescriptor.getName
              .startsWith(BigtableOpenCensusMetrics.openCensusPrefix)
          )
      ).flatMap { openCensusMetrics =>
        BigtableOpenCensusMetrics.metricCollection[IO].map { collection =>
          assertEquals(
            openCensusMetrics.count(!_.getTimeSeriesList.isEmpty),
            collection.counters.size + collection.gauges.size + collection.histograms.size + collection.summaries.size - 1
          )
        }
      }
    )
  }

  test("encounters no metric parse errors") {
    setupInstance.surround(
      BigtableOpenCensusMetrics.metricCollection[IO].map { collection =>
        assertEquals(
          collection.gauges.collectFirst {
            case ((name, _), values)
                if name.value == "prometheus4cats_opencensus_parse_errors" =>
              values.map {
                case v: LongGauge => v.value
                case _            => 0L
              }.sum
          },
          Some(0L)
        )
      }
    )
  }

}
