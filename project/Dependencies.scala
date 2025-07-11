import sbt._

object Dependencies {

  lazy val `kind-projector` = compilerPlugin(("org.typelevel" % "kind-projector" % "0.13.3").cross(CrossVersion.full))

  lazy val `scala-collection-compat` = "org.scala-lang.modules" %% "scala-collection-compat" % "2.13.0"

  lazy val `prometheus4cats-contrib-cats-effect` = Seq(
    "com.permutive" %% "prometheus4cats" % "3.0.0",
    "org.typelevel" %% "cats-effect"     % "3.5.7"
  )

  lazy val `prometheus4cats-contrib-trace4cats` = Seq(
    "com.permutive"    %% "prometheus4cats" % "3.0.0",
    "io.janstenpickle" %% "trace4cats-core" % "0.14.7"
  )

  lazy val `prometheus4cats-contrib-refreshable` = Seq(
    "com.permutive" %% "prometheus4cats" % "3.0.0",
    "com.permutive" %% "refreshable"     % "2.0.0"
  )

  lazy val `prometheus4cats-contrib-google-cloud-bigtable` = Seq(
    "com.google.cloud" % "google-cloud-bigtable" % "2.61.0",
    "com.permutive"   %% "prometheus4cats"       % "3.0.0"
  ) ++ Seq(
    "com.google.cloud" % "google-cloud-bigtable-emulator" % "0.198.0",
    "org.scalameta"   %% "munit"                          % "1.0.4",
    "org.typelevel"   %% "cats-effect-testkit"            % "3.5.7",
    "org.typelevel"   %% "munit-cats-effect"              % "2.1.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-opencensus` = Seq(
    "com.permutive" %% "prometheus4cats" % "3.0.0",
    "io.opencensus"  % "opencensus-impl" % "0.31.1"
  )

  lazy val `prometheus4cats-contrib-fs2-kafka` = Seq(
    "com.github.fd4s" %% "fs2-kafka"       % "3.8.0",
    "com.permutive"   %% "prometheus4cats" % "3.0.0"
  ) ++ Seq(
    "ch.qos.logback" % "logback-classic"            % "1.5.18", // scala-steward:of,
    "com.dimafeng"  %% "testcontainers-scala-kafka" % "0.43.0",
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.43.0",
    "com.permutive" %% "prometheus4cats-java"       % "3.0.0",
    "org.typelevel" %% "cats-effect-testkit"        % "3.5.7",
    "org.typelevel" %% "log4cats-slf4j"             % "2.7.1",
    "org.typelevel" %% "munit-cats-effect"          % "2.1.0"
  ).map(_ % Test)

}
