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

package prometheus4cats.catseffect

import java.lang.management.ManagementFactory

import scala.jdk.CollectionConverters._

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.effect.syntax.resource._
import cats.syntax.all._

import javax.management._
import prometheus4cats._

object CatsEffectMBeans {

  private val query = new QueryExp {

    override def apply(name: ObjectName): Boolean =
      name.getDomain === "cats.effect.unsafe.metrics"

    override def setMBeanServer(s: MBeanServer): Unit = ()

  }

  private val queueNumberLabel: Label.Name = "worker_queue_number"

  private val parseErrorsName: Gauge.Name = "metric_name_parse_errors"

  private val parseErrorsHelp: Metric.Help =
    "Number of errors encountered trying to parse a cats-effect JMX MBean name into a metric name"

  private val errorsName: Gauge.Name = "metric_load_errors"

  private val errorsHelp: Metric.Help =
    "Number of runtime errors encountered trying to read the value of a cats-effect JMX MBean attribute"

  private val cpuStarvationObjectName = new ObjectName(
    "cats.effect.metrics:type=CpuStarvation"
  )

  // taken from cats-effect scaladoc: https://github.com/typelevel/cats-effect/tree/series/3.x/core/jvm/src/main/scala/cats/effect/unsafe/metrics
  // and https://github.com/typelevel/cats-effect/blob/series/3.x/core/jvm/src/main/scala/cats/effect/metrics/CpuStarvationMbean.scala
  private val attributeDescriptions = Map[String, Metric.Help](
    "WorkerThreadCount"           -> "the number of worker threads backing the compute pool",
    "ActiveThreadCount"           -> "the number of active worker threads",
    "SearchingThreadCount"        -> "the number of worker threads searching for work",
    "BlockedWorkerThreadCount"    -> "the number of blocked worker threads",
    "LocalQueueFiberCount"        -> "the total number of fibers enqueued on all local queues",
    "SuspendedFiberCount"         -> "the number of asynchronously suspended fibers",
    "FiberCount"                  -> "the number of fibers enqueued on the local queue",
    "HeadIndex"                   -> "the index representing the head of the queue",
    "TailIndex"                   -> "the index representing the tail of the queue",
    "TotalFiberCount"             -> "the total number of fibers enqueued during the lifetime of the local queue",
    "TotalSpilloverCount"         -> "the total number of fibers spilt over to the external queue",
    "SuccessfulStealAttemptCount" -> "the total number of successful steal attempts by other worker threads",
    "StolenFiberCount"            -> "the total number of stolen fibers by other worker threads",
    "CpuStarvationCount"          -> "count of the number of times CPU starvation has occurred",
    "MaxClockDriftMs"             -> "the current maximum clock drift observed in milliseconds",
    "CurrentClockDriftMs"         -> "the current clock drift in milliseconds."
  )

  // these MBeans should be rendered as Prometheus counters
  private val counters = Set(
    "LocalQueueFiberCount", "TotalFiberCount", "TotalSpilloverCount", "SuccessfulStealAttemptCount", "StolenFiberCount",
    "CpuStarvationCount"
  )

  def register[F[_]: Sync](
      factory: MetricFactory.WithCallbacks[F]
  ): Resource[F, Unit] = {
    val metricFactory = factory.withPrefix("cats_effect")

    for {
      mbs    <- Sync[F].delay(ManagementFactory.getPlatformMBeanServer).toResource
      mbeans <- Sync[F]
                  .blocking(mbs.queryMBeans(null, query).asScala) // scalafix:ok
                  .toResource

      computePool = mbeans.find(
                      _.getClassName === "cats.effect.unsafe.metrics.ComputePoolSampler"
                    )
      queues = mbeans
                 .filter(
                   _.getClassName === "cats.effect.unsafe.metrics.LocalQueueSampler"
                 )
                 .toSeq

      cpuStarvation <- Sync[F]
                         .blocking(Option(mbs.getObjectInstance(cpuStarvationObjectName)))
                         .recover { case _: InstanceNotFoundException => None }
                         .toResource

      // pre-compute nicely formatted names for metrics from camelcase mbean names
      nameMap <- Sync[F]
                   .blocking(
                     (queues ++ computePool ++ cpuStarvation).toList
                       .flatTraverse(makeNameMap(mbs, _))
                       .map(_.toMap)
                       .liftTo[F]
                   )
                   .flatten
                   .toResource

      _ <- metricFactory
             .metricCollectionCallback(
               callback(mbs, nameMap, computePool, queues, cpuStarvation)
             )
             .build
    } yield ()
  }

  private def makeNameMap(
      mbs: MBeanServer,
      mbean: ObjectInstance
  ): Either[Throwable, List[(String, String)]] =
    Either.catchNonFatal(mbs.getMBeanInfo(mbean.getObjectName)).map { mbeanInfo =>
      mbeanInfo.getAttributes.map { attr =>
        attr.getName -> attr.getName
          .replaceAll("(.)(\\p{Upper}+|\\d+)", "$1_$2")
          .toLowerCase()
      }.toList
    }

  // The default MBean server implements a locking mechanism, meaning that this could block a cats-effect worker thread
  // therefore all the calls to the MBean server within a single callback are performed in one giant `blocking` blocks
  // so that they all happen in the same worker thread, but do not block other operations
  private def callback[F[_]: Sync](
      mbs: MBeanServer,
      nameMap: Map[String, String],
      computePool: Option[ObjectInstance],
      queues: Seq[ObjectInstance],
      cpuStarvation: Option[ObjectInstance]
  ): F[MetricCollection] =
    Sync[F]
      .blocking(
        for {
          computePoolRes <- computePool
                              .fold[Either[Throwable, (MetricCollection, Int, Int)]](
                                Right((MetricCollection.empty, 0, 0))
                              )(
                                readAttributes(
                                  mbs, _, MetricCollection.empty, "compute_pool", nameMap, Map.empty
                                )
                              )
          cpuStarvationRes <- cpuStarvation
                                .fold[Either[Throwable, (MetricCollection, Int, Int)]](
                                  Right(computePoolRes)
                                ) { cpuStarvationMBean =>
                                  val (col, parseErrors, errors) = computePoolRes

                                  readAttributes(
                                    mbs, cpuStarvationMBean, col, "cpu_starvation", nameMap, Map.empty
                                  ).map { case (c, pe, e) =>
                                    (c, parseErrors + pe, errors + e)
                                  }
                                }
          queuesRes <- queues
                         .foldLeft[Either[Throwable, (MetricCollection, Int, Int)]](
                           Right(cpuStarvationRes)
                         ) {
                           case (Right((col, parseErrors, errors)), mbean) =>
                             readAttributes(
                               mbs,
                               mbean,
                               col,
                               "local_queue",
                               nameMap,
                               Map(
                                 queueNumberLabel -> s"${mbean.getObjectName}"
                                   .split('-')
                                   .lastOption
                                   .getOrElse("")
                               )
                             ).map { case (col, pe, e) =>
                               (col, parseErrors + pe, errors + e)
                             }
                           case (acc, _) => acc
                         }
        } yield queuesRes._1
          .appendLongGauge(
            parseErrorsName,
            parseErrorsHelp,
            Map.empty[Label.Name, String],
            queuesRes._2.toLong
          )
          .appendLongGauge(
            errorsName,
            errorsHelp,
            Map.empty[Label.Name, String],
            queuesRes._3.toLong
          )
      )
      .flatMap(_.liftTo[F])

  private def readAttributes(
      mbs: MBeanServer,
      mbean: ObjectInstance,
      collection: MetricCollection,
      prefix: String,
      nameMap: Map[String, String],
      labels: Map[Label.Name, String]
  ): Either[Throwable, (MetricCollection, Int, Int)] = Either
    .catchNonFatal(
      mbs.getMBeanInfo(mbean.getObjectName)
    )
    .map(_.getAttributes.foldLeft((collection, 0, 0)) { case ((col, parseErrors, errors), attr) =>
      val makeMetric = attributeToMetric(
        mbs, mbean, attr, prefix, labels, nameMap, col, parseErrors, errors
      )(_)

      attr match {
        case attr if attr.getType === "int" =>
          makeMetric(_.asInstanceOf[Int].toLong)
        case attr if attr.getType === "long" =>
          makeMetric(_.asInstanceOf[Long])
        case _ => (col, parseErrors, errors)
      }

    })

  private def attributeToMetric(
      mbs: MBeanServer,
      mbean: ObjectInstance,
      attribute: MBeanAttributeInfo,
      prefix: String,
      labels: Map[Label.Name, String],
      nameMap: Map[String, String],
      collection: MetricCollection,
      parseErrors: Int,
      errors: Int
  )(convert: Object => Long): (MetricCollection, Int, Int) = {
    lazy val help = attributeDescriptions.getOrElse(
      attribute.getName,
      Metric.Help
        .from(attribute.getDescription)
        .getOrElse(Metric.Help("Cats effect MBean metric"))
    )

    def value(update: Long => MetricCollection) = Either.catchNonFatal(
      convert(mbs.getAttribute(mbean.getObjectName, attribute.getName))
    ) match {
      case Left(_)     => (collection, 0, 1)
      case Right(long) =>
        (
          update(long),
          parseErrors,
          errors + 1
        )
    }

    nameMap.get(attribute.getName).fold((collection, parseErrors, errors)) { name =>
      if (counters.contains(attribute.getName))
        Counter.Name.from(s"${prefix}_${name}_total") match {
          case Left(_)            => (collection, parseErrors + 1, errors)
          case Right(counterName) =>
            value(collection.appendLongCounter(counterName, help, labels, _))
        }
      else
        Gauge.Name.from(s"${prefix}_$name") match {
          case Left(_)          => (collection, parseErrors + 1, errors)
          case Right(gaugeName) =>
            value(collection.appendLongGauge(gaugeName, help, labels, _))
        }
    }
  }

}
