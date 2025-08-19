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

package prometheus4cats.circuit

import cats.effect._

import io.chrisdavenport.circuit._
import prometheus4cats._

trait RejectedExecutionCounter[F[_]] {

  def meteredCircuit(c: CircuitBreaker[F], circuitName: String): CircuitBreaker[F]

  def meteredResourceCircuit(
      c: CircuitBreaker[({ type lambda[x] = Resource[F, x] })#lambda],
      circuitName: String
  ): CircuitBreaker[({ type lambda[x] = Resource[F, x] })#lambda]

}

object RejectedExecutionCounter {

  /** Initialization of the Generalized Modifier which can be applied to multiple circuit breakers. */
  def register[F[_]](
      mr: MetricRegistry[F],
      metricName: Counter.Name = "circuit_rejected_execution_total"
  ): Resource[F, RejectedExecutionCounter[F]] =
    MetricFactory.builder
      .build(mr)
      .counter(metricName)
      .ofLong
      .help("Circuit Breaker Rejected Executions.")
      .label[String]("circuit_name")
      .build
      .map(new DefaultRejectedExecutionCounter(_))

  private class DefaultRejectedExecutionCounter[F[_]](
      counter: Counter[F, Long, String]
  ) extends RejectedExecutionCounter[F] {

    override def meteredCircuit(c: CircuitBreaker[F], circuitName: String): CircuitBreaker[F] =
      c.doOnRejected(counter.inc(circuitName))

    override def meteredResourceCircuit(
        c: CircuitBreaker[({ type lambda[x] = Resource[F, x] })#lambda],
        circuitName: String
    ): CircuitBreaker[({ type lambda[x] = Resource[F, x] })#lambda] =
      c.doOnRejected(counter.mapK(Resource.liftK).inc(circuitName))

  }

  /** Single Metered Circuit */
  def meteredCircuit[F[_]](
      mr: MetricRegistry[F],
      metricName: Counter.Name,
      circuit: CircuitBreaker[F]
  ): Resource[F, CircuitBreaker[F]] =
    MetricFactory.builder
      .build(mr)
      .counter(metricName)
      .ofLong
      .help("Circuit Breaker Rejected Executions.")
      .build
      .map(counter => circuit.doOnRejected(counter.inc))

}
