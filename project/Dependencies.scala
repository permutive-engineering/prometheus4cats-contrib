import sbt._

object Dependencies {

  lazy val `kind-projector` = compilerPlugin(("org.typelevel" % "kind-projector" % "0.13.4").cross(CrossVersion.full))

  lazy val `prometheus4cats-contrib-cats-effect` = Seq(
    "com.permutive" %% "prometheus4cats" % "5.0.1",
    "org.typelevel" %% "cats-effect"     % "3.7.0"
  )

  lazy val `prometheus4cats-contrib-trace4cats` = Seq(
    "com.permutive"    %% "prometheus4cats" % "5.0.1",
    "io.janstenpickle" %% "trace4cats-core" % "0.14.7"
  )

  lazy val `prometheus4cats-contrib-refreshable` = Seq(
    "com.permutive" %% "prometheus4cats" % "5.0.1",
    "com.permutive" %% "refreshable"     % "2.1.1"
  )

  lazy val `prometheus4cats-contrib-google-cloud-bigtable` = Seq(
    "com.google.cloud" % "google-cloud-bigtable" % "2.78.0",
    "com.permutive"   %% "prometheus4cats"       % "5.0.1"
  ) ++ Seq(
    "com.google.cloud" % "google-cloud-bigtable-emulator" % "0.215.0",
    "org.scalameta"   %% "munit"                          % "1.2.4",
    "org.typelevel"   %% "cats-effect-testkit"            % "3.6.3",
    "org.typelevel"   %% "munit-cats-effect"              % "2.1.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-opencensus` = Seq(
    "com.permutive" %% "prometheus4cats" % "5.0.1",
    "io.opencensus"  % "opencensus-impl" % "0.31.1"
  )

  lazy val `prometheus4cats-contrib-fs2-kafka` = Seq(
    "org.typelevel" %% "fs2-kafka"       % "4.0.0",
    "com.permutive"   %% "prometheus4cats" % "5.0.1"
  ) ++ Seq(
    "com.dimafeng"  %% "testcontainers-scala-kafka" % "0.44.1",
    "com.dimafeng"  %% "testcontainers-scala-munit" % "0.44.1",
    "com.permutive" %% "prometheus4cats-java"       % "5.0.1",
    "org.typelevel" %% "cats-effect-testkit"        % "3.6.3",
    "org.typelevel" %% "munit-cats-effect"          % "2.1.0"
  ).map(_ % Test)

  lazy val `prometheus4cats-contrib-circuit` = Seq(
    "com.permutive"     %% "prometheus4cats" % "5.0.1",
    "io.chrisdavenport" %% "circuit"         % "0.5.1"
  )

}
