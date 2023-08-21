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

package prometheus4cats.fs2kafka

import java.util.UUID

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.dimafeng.testcontainers.KafkaContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import fs2.kafka._
import io.prometheus.client.CollectorRegistry
import munit.CatsEffectSuite
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import prometheus4cats.MetricFactory
import prometheus4cats.javasimpleclient.JavaMetricRegistry

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class KafkaMetricsSuite extends CatsEffectSuite with TestContainerForAll {
  override val containerDef: KafkaContainer.Def = KafkaContainer.Def()

  private[this] def nextTopicName: IO[String] =
    IO.blocking(s"topic-${UUID.randomUUID()}")

  final def withTopic[A](f: String => IO[A]): IO[A] = for {
    topic <- nextTopicName
    _ <- createCustomTopic(topic, Map.empty, 1)
    a <- f(topic)
  } yield a

  protected def adminClient: Resource[IO, KafkaAdminClient[IO]] =
    withContainers { container =>
      KafkaAdminClient
        .resource[IO](
          AdminClientSettings(container.bootstrapServers)
            .withClientId("test-kafka-admin-client")
            .withRequestTimeout(10.seconds)
            .withConnectionsMaxIdle(10.seconds)
        )
    }

  def createCustomTopic(
      topic: String,
      topicConfig: Map[String, String],
      partitions: Int
  ): IO[Unit] = {
    val newTopic = new NewTopic(topic, partitions, 1.toShort)
      .configs(topicConfig.asJava)

    adminClient.use(_.createTopic(newTopic))
  }

  final def consumerResource[K, V](implicit
      kd: RecordDeserializer[IO, K],
      vd: RecordDeserializer[IO, V]
  ): Resource[IO, KafkaConsumer[IO, K, V]] =
    KafkaConsumer
      .resource(
        ConsumerSettings[IO, K, V]
          .withProperties(defaultConsumerProperties)
          .withRecordMetadata(_.timestamp.toString)
          .withIsolationLevel(IsolationLevel.ReadCommitted)
      )

  final def producerResource[K, V](implicit
      k: RecordSerializer[IO, K],
      v: RecordSerializer[IO, V]
  ): Resource[IO, KafkaProducer.Metrics[IO, K, V]] =
    KafkaProducer.resource(
      ProducerSettings[IO, K, V].withProperties(defaultProducerConfig)
    )

  final def transactionalProducerResource[K, V](implicit
      k: RecordSerializer[IO, K],
      v: RecordSerializer[IO, V]
  ): Resource[IO, TransactionalKafkaProducer.WithoutOffsets[IO, K, V]] =
    TransactionalKafkaProducer.resource(
      TransactionalProducerSettings[IO, K, V](
        UUID.randomUUID().toString,
        ProducerSettings[IO, K, V]
          .withProperties(defaultProducerConfig)
          .withRetries(1)
      )
    )

  final def defaultProducerConfig = withContainers { container =>
    Map[String, String](
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers,
      ProducerConfig.MAX_BLOCK_MS_CONFIG -> 10000.toString,
      ProducerConfig.RETRY_BACKOFF_MS_CONFIG -> 1000.toString
    )
  }

  final def defaultConsumerProperties: Map[String, String] = withContainers {
    container =>
      Map(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
        ConsumerConfig.GROUP_ID_CONFIG -> "test-group-id",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false"
      )
  }

  // Use slf4j + logback to see any warnings in the logs
  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val factory =
    Resource.eval(IO.delay(new CollectorRegistry())).flatMap { reg =>
      JavaMetricRegistry
        .Builder()
        .withRegistry(reg)
        .build[IO]
        .map(MetricFactory.builder.build(_) -> reg)
    }

  test("registers consumer metrics") {

    val consumerGroup = "test"

    withTopic { topic =>
      (
        consumerResource[String, String],
        producerResource[String, String],
        factory
      ).tupled.use { case (consumer, producer, (metrics, registry)) =>
        KafkaMetrics
          .registerConsumerCallback(metrics, consumer, consumerGroup)
          .surround(
            producer.produce(
              ProducerRecords
                .one(ProducerRecord(topic, "test", "test"))
            ) >>
              consumer.subscribeTo(
                topic
              ) >> consumer.stream.take(1).compile.drain >> IO
                .delay(registry.metricFamilySamples().asScala.toList)
                .map { samples =>
                  assertEquals(
                    samples
                      .find(
                        _.name == "prometheus4cats_collection_callback_duplicates"
                      )
                      .map(_.samples.isEmpty),
                    Some(true)
                  )
                  assert(
                    samples
                      .exists(_.name.startsWith("kafka_client_consumer"))
                  )
                }
          )
      }

    }
  }

  test("registers consumer metrics for multiple consumers") {

    val consumerGroup1 = "test1"
    val consumerGroup2 = "test2"

    withTopic { topic1 =>
      withTopic { topic2 =>
        (
          consumerResource[String, String],
          consumerResource[String, String],
          producerResource[String, String],
          factory
        ).tupled.use {
          case (consumer1, consumer2, producer, (metrics, registry)) =>
            KafkaMetrics
              .registerConsumerCallback(metrics, consumer1, consumerGroup1)
              .surround(
                producer.produce(
                  ProducerRecords
                    .one(ProducerRecord(topic1, "test", "test"))
                ) >> consumer1
                  .subscribeTo(
                    topic1
                  ) >> consumer1.stream.take(1).compile.drain >> KafkaMetrics
                  .registerConsumerCallback(metrics, consumer2, consumerGroup2)
                  .surround(
                    producer
                      .produce(
                        ProducerRecords
                          .one(ProducerRecord(topic2, "test", "test"))
                      ) >> consumer2
                      .subscribeTo(
                        topic2
                      ) >> consumer2.stream.take(1).compile.drain >> IO
                      .delay(registry.metricFamilySamples().asScala.toList)
                      .map { samples =>
                        assertEquals(
                          samples
                            .find(
                              _.name == "prometheus4cats_collection_callback_duplicates"
                            )
                            .map(_.samples.isEmpty),
                          Some(true)
                        )
                        assert(
                          samples.exists(
                            _.name.startsWith("kafka_client_consumer")
                          )
                        )
                      }
                  )
              )
        }
      }
    }
  }

  test("registers producer metrics") {

    val producerName = "test"

    withTopic { topic =>
      (producerResource[String, String], factory).tupled.use {
        case (producer, (metrics, registry)) =>
          KafkaMetrics
            .registerProducerCallback(metrics, producer, producerName)
            .surround(
              producer
                .produce(
                  ProducerRecords(
                    List(ProducerRecord(topic, "test", "test"))
                  )
                )
                .flatten >> IO
                .delay(registry.metricFamilySamples().asScala.toList)
                .map { samples =>
                  assertEquals(
                    samples
                      .find(
                        _.name == "prometheus4cats_collection_callback_duplicates"
                      )
                      .map(_.samples.isEmpty),
                    Some(true)
                  )
                  assert(
                    samples.exists(_.name.startsWith("kafka_client_producer"))
                  )
                  assert(
                    samples
                      .exists(_.name.startsWith("kafka_client_producer_node"))
                  )
                }
            )

      }

    }
  }

  test("registers producer metrics for multiple producers") {

    val producerName1 = "test1"
    val producerName2 = "test2"

    withTopic { topic =>
      val records = ProducerRecords(List(ProducerRecord(topic, "test", "test")))

      (
        producerResource[String, String],
        producerResource[String, String],
        factory
      ).tupled.use { case (producer1, producer2, (metrics, registry)) =>
        KafkaMetrics
          .registerProducerCallback(metrics, producer1, producerName1)
          .surround(
            producer1
              .produce(
                records
              )
              .flatten >> KafkaMetrics
              .registerProducerCallback(metrics, producer2, producerName2)
              .surround(
                producer2
                  .produce(
                    records
                  )
                  .flatten >> IO
                  .delay(registry.metricFamilySamples().asScala.toList)
                  .map { samples =>
                    assert(
                      samples
                        .find(
                          _.name == "prometheus4cats_collection_callback_duplicates"
                        )
                        .forall(_.samples.isEmpty)
                    )
                    assert(
                      samples.exists(_.name.startsWith("kafka_client_producer"))
                    )
                    assert(
                      samples
                        .exists(_.name.startsWith("kafka_client_producer_node"))
                    )
                  }
              )
          )
      }

    }
  }

  test("registers transactional producer metrics") {

    val producerName = "test"

    withTopic { topic =>
      (
        transactionalProducerResource[String, String],
        factory
      ).tupled.use { case (producer, (metrics, registry)) =>
        KafkaMetrics
          .registerTransactionalProducerCallback(
            metrics,
            producer,
            producerName
          )
          .surround(
            producer
              .produceWithoutOffsets(
                ProducerRecords(List(ProducerRecord(topic, "test", "test")))
              ) >> IO
              .delay(registry.metricFamilySamples().asScala.toList)
              .map { samples =>
                assertEquals(
                  samples
                    .find(
                      _.name == "prometheus4cats_collection_callback_duplicates"
                    )
                    .map(_.samples.isEmpty),
                  Some(true)
                )
                assert(
                  samples.exists(_.name.startsWith("kafka_client_producer"))
                )
                assert(
                  samples
                    .exists(_.name.startsWith("kafka_client_producer_node"))
                )
              }
          )
      }

    }
  }

  test("registers transactional producer metrics for multiple producers") {

    val producerName1 = "test1"
    val producerName2 = "test2"

    withTopic { topic =>
      val records = ProducerRecords(List(ProducerRecord(topic, "test", "test")))

      (
        transactionalProducerResource[String, String],
        transactionalProducerResource[String, String],
        factory
      ).tupled.use { case (producer1, producer2, (metrics, registry)) =>
        KafkaMetrics
          .registerTransactionalProducerCallback(
            metrics,
            producer1,
            producerName1
          )
          .surround(
            producer1
              .produceWithoutOffsets(
                records
              ) >> KafkaMetrics
              .registerTransactionalProducerCallback(
                metrics,
                producer2,
                producerName2
              )
              .surround(
                producer2
                  .produceWithoutOffsets(
                    records
                  ) >> IO
                  .delay(registry.metricFamilySamples().asScala.toList)
                  .map { samples =>
                    assertEquals(
                      samples
                        .find(
                          _.name == "prometheus4cats_collection_callback_duplicates"
                        )
                        .map(_.samples.isEmpty),
                      Some(true)
                    )
                    assert(
                      samples.exists(
                        _.name.startsWith("kafka_client_producer")
                      )
                    )
                    assert(
                      samples.exists(
                        _.name.startsWith("kafka_client_producer_node")
                      )
                    )
                  }
              )
          )
      }

    }
  }
}
