// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "2.0" // your current series x.y

ThisBuild / organization := "com.permutive"
ThisBuild / organizationName := "Permutive"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("janstenpickle", "Chris Jansen")
)

// publish to s01.oss.sonatype.org (set to true to publish to oss.sonatype.org instead)
ThisBuild / tlSonatypeUseLegacyHost := true

// publish website from this branch
ThisBuild / tlSitePublishBranch := Some("main")

val Scala213 = "2.13.13"
ThisBuild / crossScalaVersions := Seq("2.12.19", Scala213, "3.3.3")
ThisBuild / scalaVersion := Scala213 // the default Scala

val Prometheus4Cats = "2.0.0"

val CollectionCompat = "2.12.0"

lazy val root =
  tlCrossRootProject.aggregate(
    catsEffect,
    trace4Cats,
    refreshable,
    googleCloudBigtable,
    opencensus,
    fs2Kafka
  )

lazy val catsEffect = project
  .in(file("cats-effect"))
  .settings(
    name := "prometheus4cats-contrib-cats-effect",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "org.typelevel" %% "cats-effect" % "3.4.8"
    ),
    libraryDependencies ++= PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, 12)) =>
          "org.scala-lang.modules" %% "scala-collection-compat" % CollectionCompat
      }
      .toList
  )

lazy val trace4Cats = project
  .in(file("trace4cats"))
  .settings(
    name := "prometheus4cats-contrib-trace4cats",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "io.janstenpickle" %% "trace4cats-core" % "0.14.7"
    )
  )

lazy val refreshable = project
  .in(file("refreshable"))
  .settings(
    name := "prometheus4cats-contrib-refreshable",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "com.permutive" %% "refreshable" % "2.0.0"
    )
  )

lazy val googleCloudBigtable = project
  .in(file("bigtable"))
  .settings(
    name := "prometheus4cats-contrib-google-cloud-bigtable",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "com.google.cloud" % "google-cloud-bigtable" % "2.20.4",
      "org.scalameta" %%% "munit" % "0.7.29" % Test,
      "org.typelevel" %%% "munit-cats-effect-3" % "1.0.7" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % "3.4.5" % Test,
      "com.google.cloud" % "google-cloud-bigtable-emulator" % "0.155.4" % Test
    )
  )
  .dependsOn(opencensus)

lazy val opencensus = project
  .in(file("opencensus"))
  .settings(
    name := "prometheus4cats-contrib-opencensus",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "io.opencensus" % "opencensus-impl" % "0.31.1"
    ),
    libraryDependencies ++= PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, 12)) =>
          "org.scala-lang.modules" %% "scala-collection-compat" % CollectionCompat
      }
      .toList
  )

lazy val fs2Kafka = project
  .in(file("fs2-kafka"))
  .settings(
    name := "prometheus4cats-contrib-fs2-kafka",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "com.github.fd4s" %% "fs2-kafka" % "3.5.0",
      "com.dimafeng" %% "testcontainers-scala-munit" % "0.40.14" % Test,
      "com.dimafeng" %% "testcontainers-scala-kafka" % "0.40.14" % Test,
      "com.permutive" %% "prometheus4cats-java" % Prometheus4Cats % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.11" % Test, // scala-steward:off
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
      "org.typelevel" %% "cats-effect-testkit" % "3.4.5" % Test,
      "org.typelevel" %% "log4cats-slf4j" % "2.5.0" % Test
    ),
    libraryDependencies ++= PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, 12)) =>
          "org.scala-lang.modules" %% "scala-collection-compat" % CollectionCompat
      }
      .toList
  )

lazy val docs = project.in(file("site")).enablePlugins(TypelevelSitePlugin)
