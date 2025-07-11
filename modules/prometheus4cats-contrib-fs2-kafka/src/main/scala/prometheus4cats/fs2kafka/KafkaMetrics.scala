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

package prometheus4cats.fs2kafka

import scala.collection.immutable.Map
import scala.collection.immutable.Set
import scala.jdk.CollectionConverters._

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._

import fs2.kafka.KafkaConsumer
import fs2.kafka.KafkaProducer
import fs2.kafka.TransactionalKafkaProducer
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.{Metric => KafkaMetric}
import prometheus4cats._

object KafkaMetrics {

  sealed trait ParseError extends Any {

    def metricName: String

    def labelValue: String

  }

  object ParseError {

    final case class InvalidName(metricName: String) extends AnyVal with ParseError {

      override def labelValue: String = "invalid_name"

    }

    final case class InvalidMetric(metricName: String) extends AnyVal with ParseError {

      override def labelValue: String = "invalid_metric_type"

    }

    final case class InvalidLabels(metricName: String) extends AnyVal with ParseError {

      override def labelValue: String = "invalid_label_names"

    }

  }

  private val parseErrorGaugeName: Gauge.Name = "metric_parse_error"

  private val parseErrorHelp: Metric.Help =
    "Kafka metrics that have been excluded due to invalid strings"

  private val parseErrorTypeLabel: Label.Name = "parse_error_type"

  private val parseErrorMetricNameLabel: Label.Name = "metric_name"

  private val metricsPrefix: Metric.Prefix = "kafka_client"

  private val consumerGroupLabel: Label.Name = "consumer_group"

  private val producerNameLabel: Label.Name = "producer_name"

  private val transactionalProducerLabel: Label.Name = "is_transactional"

  private val tagFilter: Set[String] = Set("client-id")

  private def transformMetrics[F[_]: Sync](
      extraLabels: Map[Label.Name, String],
      kafkaMetrics: F[Map[MetricName, KafkaMetric]]
  ): F[MetricCollection] = {
    def transformName(kafkaName: MetricName): String =
      s"${kafkaName.group().replace('-', '_').replace("_metrics", "")}_${kafkaName.name().replace('-', '_')}"

    // Doing Label.Name.unsafeFrom here prevents us from having a nasty traverse - which would be harder to read and
    // possibly less performant.
    def transformLabels(
        metricName: MetricName,
        labels: Map[String, String]
    ): Either[ParseError, Map[Label.Name, String]] =
      Either.catchNonFatal {
        labels.flatMap { case (key, value) =>
          if (tagFilter.contains(key)) None
          else Some(Label.Name.unsafeFrom(key.replace('-', '_')) -> value)
        } + (Label.Name("metric_group") -> metricName.group())
      }
        .leftMap(_ => ParseError.InvalidLabels(metricName.name()))

    /*
    Kafka client metrics are crazy.

    Calls to `value` to get a double are deprecated. This is because, inexplicably, Kafka metrics are allowed
    to not return a number(!?).

    If the metric does return a number then you have to check the type and cast, note that
    pattern matching won't work here because we are dealing with native Java doubles, unless it's an integer,
    then it's an object(!?).

    If the metric doesn't return a number then an exception is thrown(!?), this should hopefully never
    happen so I'm just letting it fail, so the reason why a non-numeric metric got into the Kafka metric registry can
    be investigated.
     */
    def convertMetricValue[A](
        metric: KafkaMetric
    )(onLong: Long => A, onDouble: Double => A): Either[ParseError, A] = {
      val value = metric.metricValue()

      if (value.isInstanceOf[Double])
        Right(onDouble(value.asInstanceOf[Double]))
      else if (value.isInstanceOf[java.lang.Integer])
        Right(onLong(value.asInstanceOf[java.lang.Integer].longValue()))
      else
        Left(ParseError.InvalidMetric(metric.metricName().name()))
    }

    def createCounter(
        metricName: String,
        help: prometheus4cats.Metric.Help,
        labels: Map[Label.Name, String],
        metric: KafkaMetric,
        col: MetricCollection
    ): Either[ParseError, MetricCollection] =
      Counter.Name
        .from(metricName)
        .leftMap(ParseError.InvalidName(_))
        .flatMap(name =>
          convertMetricValue(metric)(
            col.appendLongCounter(name, help, labels, _),
            col.appendDoubleCounter(name, help, labels, _)
          )
        )

    def createGauge(
        metricName: String,
        help: prometheus4cats.Metric.Help,
        labels: Map[Label.Name, String],
        metric: KafkaMetric,
        col: MetricCollection
    ): Either[ParseError, MetricCollection] =
      Gauge.Name
        .from(metricName)
        .leftMap(ParseError.InvalidName(_))
        .flatMap(name =>
          convertMetricValue(metric)(
            col.appendLongGauge(name, help, labels, _),
            col.appendDoubleGauge(name, help, labels, _)
          )
        )

    /* This rebuilds the labels so that the map contains exactly the same keyset as the labels already registered for the
    metric name.
     */
    def conformLabels(
        expected: Set[String],
        labels: Map[String, String]
    ): Map[String, String] =
      expected.foldLeft(Map.empty[String, String]) { case (map, l) =>
        map.updated(l, labels.getOrElse(l, ""))
      }

    kafkaMetrics.flatMap { metrics =>
      /*
          Reading metric values needs to be blocking because Kafka metrics used a `synchronized` block when reading, this
          is technically blocking, so CE will need to put the operation in its own thread.

          Doing this in a single `blocking` call is better than one for each metric, so we don't end up spawning
          (or reusing a cached) thread for each metric in the Kafka metrics map.
       */
      Sync[F].blocking {
        /*
            Metric labels can sometimes be mis-aligned and therefore we need to initial pass making sure all the labels
            conform before adding them to a metric collection
         */
        val aligned =
          metrics.foldLeft[
            Map[String, Map[MetricName, (KafkaMetric, Map[String, String])]]
          ](Map.empty) { case (acc, (name, metric)) =>
            val transformedName = transformName(name)
            val labels          = name.tags().asScala.toMap

            acc.get(transformedName) match {
              case Some(metrics) =>
                val allKeys = metrics.view
                  .flatMap(_._2._2.keySet)
                  .toSet ++ labels.keySet

                val newMetrics = metrics
                  .updated(name, (metric, labels))
                  .map { case (k, (metric, labels)) =>
                    (k, (metric, conformLabels(allKeys, labels)))
                  }

                acc.updated(transformedName, newMetrics)
              case None =>
                acc.updated(transformedName, Map((name, (metric, labels))))
            }
          }

        val (metricCollection, errors) = aligned.foldLeft(
          (MetricCollection.empty, List.empty[ParseError])
        ) { case ((col, parseErrors), (metricName, metrics)) =>
          metrics.foldLeft((col, parseErrors)) { case ((col, parseErrors), (name, (metric, labels))) =>
            val newCol = for {
              transformedLabels <- transformLabels(name, labels)
              allLabels          = transformedLabels ++ extraLabels
              help = prometheus4cats.Metric.Help
                       .from(name.description())
                       .getOrElse(
                         prometheus4cats.Metric.Help("Metric from kafka")
                       )
              newCol <-
                if (metricName.endsWith("_total"))
                  createCounter(metricName, help, allLabels, metric, col)
                else createGauge(metricName, help, allLabels, metric, col)
            } yield newCol

            newCol.fold(
              err => (col, err :: parseErrors),
              (_, parseErrors)
            )

          }

        }

        val errorGaugeValues = IterableUtils
          .groupMapReduce(errors)(identity)(_ => 1L)(_ + _)
          .view
          .map { case (err, v) =>
            v -> (IndexedSeq(
              err.labelValue,
              err.metricName
            ) ++ extraLabels.values)
          }
          .toList

        metricCollection.appendLongGauge(
          parseErrorGaugeName,
          parseErrorHelp,
          IndexedSeq(
            parseErrorTypeLabel,
            parseErrorMetricNameLabel
          ) ++ extraLabels.keys,
          errorGaugeValues
        )

      }

    }
  }

  def registerConsumerCallback[F[_]: Sync, K, V](
      metricFactory: MetricFactory.WithCallbacks[F],
      consumer: KafkaConsumer[F, K, V],
      consumerGroup: String
  ): Resource[F, Unit] = metricFactory
    .withPrefix(metricsPrefix)
    .metricCollectionCallback(
      transformMetrics(
        Map(consumerGroupLabel -> consumerGroup),
        consumer.metrics
      )
    )
    .build

  def registerProducerCallback[F[_]: Sync, K, V](
      metricFactory: MetricFactory.WithCallbacks[F],
      producer: KafkaProducer.Metrics[F, K, V],
      producerName: String
  ): Resource[F, Unit] = metricFactory
    .withPrefix(metricsPrefix)
    .metricCollectionCallback(
      transformMetrics(
        Map(
          producerNameLabel          -> producerName,
          transactionalProducerLabel -> "false"
        ),
        producer.metrics
      )
    )
    .build

  def registerTransactionalProducerCallback[F[_]: Sync, K, V](
      metricFactory: MetricFactory.WithCallbacks[F],
      producer: TransactionalKafkaProducer.Metrics[F, K, V],
      producerName: String
  ): Resource[F, Unit] = metricFactory
    .withPrefix(metricsPrefix)
    .metricCollectionCallback(
      transformMetrics(
        Map(
          producerNameLabel          -> producerName,
          transactionalProducerLabel -> "true"
        ),
        producer.metrics
      )
    )
    .build

}
