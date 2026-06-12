import sbt.*

object Dependencies {

  lazy val `kind-projector` = compilerPlugin(("org.typelevel" % "kind-projector" % "0.13.4").cross(CrossVersion.full))

  lazy val `prometheus4cats-contrib-cats-effect` = Seq(
    "com.permutive" %% "prometheus4cats"          % "6.0.0-RC3",
    "io.prometheus"  % "prometheus-metrics-model" % "1.6.1",
    "org.typelevel" %% "cats-effect"              % "3.7.0"
  )

  lazy val `prometheus4cats-contrib-trace4cats` = Seq(
    "com.permutive"    %% "prometheus4cats" % "6.0.0-RC3",
    "io.janstenpickle" %% "trace4cats-core" % "0.14.7"
  )

  lazy val `prometheus4cats-contrib-refreshable` = Seq(
    "com.permutive" %% "prometheus4cats" % "6.0.0-RC3",
    "com.permutive" %% "refreshable"     % "2.1.1"
  )

  lazy val `prometheus4cats-contrib-google-cloud-bigtable` = Seq(
    "com.google.cloud" % "google-cloud-bigtable"    % "2.71.0",
    "com.permutive"   %% "prometheus4cats"          % "6.0.0-RC3",
    "io.prometheus"    % "prometheus-metrics-model" % "1.6.1"
  ) ++ Seq(
    "com.google.cloud" % "google-cloud-bigtable-emulator" % "0.215.0",
    "org.scalameta"   %% "munit"                          % "1.2.4",
    "org.typelevel"   %% "cats-effect-testkit"            % "3.7.0",
    "org.typelevel"   %% "munit-cats-effect"              % "2.2.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-opencensus` = Seq(
    "com.permutive" %% "prometheus4cats" % "6.0.0-RC3",
    "io.opencensus"  % "opencensus-impl" % "0.31.1"
  )

  lazy val `prometheus4cats-contrib-fs2-kafka` = Seq(
    "com.github.fd4s" %% "fs2-kafka"          % "3.9.1",
    "com.permutive" %% "prometheus4cats"          % "6.0.0-RC3",
    "io.prometheus"  % "prometheus-metrics-model" % "1.6.1"
  ) ++ Seq(
    "com.dimafeng"  %% "testcontainers-scala-kafka" % "0.44.1",
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.44.1",
    "com.permutive" %% "prometheus4cats-java"       % "6.0.0-RC3",
    "org.typelevel" %% "cats-effect-testkit"        % "3.7.0",
    "org.typelevel" %% "munit-cats-effect"          % "2.2.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-circuit` = Seq(
    "com.permutive"     %% "prometheus4cats" % "6.0.0-RC3",
    "io.chrisdavenport" %% "circuit"         % "0.5.1"
  )

}
