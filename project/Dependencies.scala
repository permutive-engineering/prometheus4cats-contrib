import sbt._

object Dependencies {

  lazy val `kind-projector` = compilerPlugin(("org.typelevel" % "kind-projector" % "0.13.3").cross(CrossVersion.full))

  lazy val `prometheus4cats-contrib-cats-effect` = Seq(
    "com.permutive" %% "prometheus4cats" % "4.0.1",
    "org.typelevel" %% "cats-effect"     % "3.6.2"
  )

  lazy val `prometheus4cats-contrib-trace4cats` = Seq(
    "com.permutive"    %% "prometheus4cats" % "4.0.1",
    "io.janstenpickle" %% "trace4cats-core" % "0.14.7"
  )

  lazy val `prometheus4cats-contrib-refreshable` = Seq(
    "com.permutive" %% "prometheus4cats" % "4.0.1",
    "com.permutive" %% "refreshable"     % "2.1.0"
  )

  lazy val `prometheus4cats-contrib-google-cloud-bigtable` = Seq(
    "com.google.cloud" % "google-cloud-bigtable" % "2.61.0",
    "com.permutive"   %% "prometheus4cats"       % "4.0.1"
  ) ++ Seq(
    "com.google.cloud" % "google-cloud-bigtable-emulator" % "0.198.0",
    "org.scalameta"   %% "munit"                          % "1.1.1",
    "org.typelevel"   %% "cats-effect-testkit"            % "3.6.2",
    "org.typelevel"   %% "munit-cats-effect"              % "2.1.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-opencensus` = Seq(
    "com.permutive" %% "prometheus4cats" % "4.0.1",
    "io.opencensus"  % "opencensus-impl" % "0.31.1"
  )

  lazy val `prometheus4cats-contrib-fs2-kafka` = Seq(
    "com.github.fd4s" %% "fs2-kafka"       % "3.8.0",
    "com.permutive"   %% "prometheus4cats" % "4.0.1"
  ) ++ Seq(
    "com.dimafeng"  %% "testcontainers-scala-kafka" % "0.43.0",
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.43.0",
    "com.permutive" %% "prometheus4cats-java"       % "4.0.1",
    "org.typelevel" %% "cats-effect-testkit"        % "3.6.2",
    "org.typelevel" %% "munit-cats-effect"          % "2.1.0"
  ).map(_ % Test)

}
