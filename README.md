# Prometheus4Cats Contrib

This repository contains a series of integrations for [Prometheus4Cats] that instrument other libraries or metric
sources. Each integration is documented below:

## Bigtable

Google Bigtable [Java client instrumentation](https://cloud.google.com/bigtable/docs/client-side-metrics).
Uses a [Prometheus4Cats] callback registry to inspect the client metrics registered with OpenCensus.

Use the [`BigtableOpenCensusMetrics`](/modules/prometheus4cats-contrib-google-cloud-bigtable/src/main/scala/prometheus4cats/bigtable/BigtableOpenCensusMetrics.scala)
smart constructor to enable metric collection on the Java client and registering them with a
[`MetricFactory.WithCallbacks`].

```sbt
"com.permutive" %% "prometheus4cats-contrib-google-cloud-bigtable" % "4.0.0"
```

## Cats-Effect

[Cats-Effect] metrics taken from JMX MBeans. This includes a
[CPU starvation](https://typelevel.org/cats-effect/docs/core/starvation-and-tuning) counter.

Use the [`CatsEffectMBeans`](/modules/prometheus4cats-contrib-cats-effect/src/main/scala/prometheus4cats/catseffect/CatsEffectMBeans.scala)
smart constructor register callbacks for [Cats-Effect] MBeans with [`MetricFactory.WithCallbacks`].

```sbt
"com.permutive" %% "prometheus4cats-contrib-cats-effect" % "4.0.0"
```

## FS2-Kafka

[FS2-Kafka] integration that instruments consumers and producers. Uses a [Prometheus4Cats] callback registry to inspect
built-in [Kafka client metrics](https://docs.confluent.io/platform/current/kafka/monitoring.html#).

Use the smart constructors in [`KafkaMetrics`](/modules/prometheus4cats-contrib-fs2-kafka/src/main/scala/prometheus4cats/fs2kafka/KafkaMetrics.scala)
to register callbacks for producers and consumers with a [`MetricFactory.WithCallbacks`].

```sbt
"com.permutive" %% "prometheus4cats-contrib-fs2-kafka" % "4.0.0"
```

## Refreshable

Instrumented implementation of [Refreshable]. Provides the following metrics:

| Metric Name                          | Labels                            | Metric Type | Description                                                                                                 |
|--------------------------------------|-----------------------------------|-------------|-------------------------------------------------------------------------------------------------------------|
| `refreshable_read_total`             | `refreshable_name`, `value_state` | Counter     | Number of times this Refreshable has been read, with a label denoting the state of the value                |
| `refreshable_is_running`             | `refreshable_name`                | Gauge       | Whether this Refreshable is running - `1` if true, `0` if false                                             |
| `refreshable_retries_exhausted`      | `refreshable_name`                | Gauge       | Whether retries have been exhausted for this Refreshable - `1` if true, `0` if false                        |
| `refreshable_refresh_failing`        | `refreshable_name`                | Gauge       | Whether refresh is currently failing - `1` if true, `0` if false                                            |
| `refreeshable_refresh_success_total` | `refreshable_name`                | Counter     | Number of times the refresh operation has succeeded                                                         |
| `refreshable_refresh_failure_total`  | `refreshable_name`                | Counter     | Number of times refresh failed                                                                              |
| `refreshable_status`                 | `refreshable_name`, `value_state` | Gauge       | The current status of this Refreshable - a value of `1` against the label value indicates the current state |

Use the [`InstrumentedRefreshable`](/modules/prometheus4cats-contrib-refreshable/src/main/scala/prometheus4cats/refreshable/InstrumentedRefreshable.scala)
smart constructor to instrument a given `Refreshable` when used with an instance of [`MetricFactory.WithCallbacks`].

```sbt
"com.permutive" %% "prometheus4cats-contrib-refreshable" % "4.0.0"
```

## Trace4Cats

Instrumented implementations of [Trace4Cats] interfaces. Provides the following metrics:

| Interface       | Metric Name                          | Labels                                    | Metric Type | Description                                        |
|-----------------|--------------------------------------|-------------------------------------------|-------------|----------------------------------------------------|
| `EntryPoint`    | `trace4cats_entry_point_spans_total` | `span_kind`, `is_root`, `sample_decision` | Counter     | Total number of spans created                      |
| `SpanCompleter` | `trace4cats_completer_spans_total`   | `completer_name`                          | Counter     | Total number of spans completed                    |
| `SpanCompleter` | `trace4cats_completer_complete_time` | `completer_name`                          | Histogram   | Time it takes to complete a span in seconds        |
| `SpanExporter`  | `trace4cats_exporter_batches_total`  | `exporter_name`                           | Counter     | Total number of batches sent via this exporter     |
| `SpanExporter`  | `trace4cats_exporter_export_time`    | `exporter_name`                           | Histogram   | Time it takes to export a span batch in seconds    |
| `SpanExporter`  | `trace4cats_exporter_batch_size`     | `exporter_name`                           | Histogram   | Size distribution of batches sent by this exporter |

Use the [`InstrumentedEntrypoint`](/modules/prometheus4cats-contrib-trace4cats/src/main/scala/prometheus4cats/trace4cats/InstrumentedEntrypoint.scala),
[`InstrumentedSpanCompleter`](/modules/prometheus4cats-contrib-trace4cats/src/main/scala/prometheus4cats/trace4cats/InstrumentedSpanCompleter.scala) and
[`InstrumentedSpanExporter`](/modules/prometheus4cats-contrib-trace4cats/src/main/scala/prometheus4cats/trace4cats/InstrumentedSpanExporter.scala) with
a [`MetricFactory`] to return instrumented wrappers of the underlying implementations.

```sbt
"com.permutive" %% "prometheus4cats-contrib-trace4cats" % "4.0.0"
```

[Cats-Effect]: https://typelevel.org/cats-effect
[FS2-Kafka]: https://fd4s.github.io/fs2-kafka/
[Refreshable]: https://github.com/permutive-engineering/refreshable
[Trace4Cats]: https://github.com/trace4cats/trace4cats
[Prometheus4Cats]: https://github.com/permutive-engineering/prometheus4cats
[`MetricFactory`]: https://permutive-engineering.github.io/prometheus4cats/docs/interface/metric-factory/
[`MetricFactory.WithCallbacks`]: https://permutive-engineering.github.io/prometheus4cats/docs/interface/metric-factory/#metricfactory-or-metricfactorywithcallbacks
