// https://typelevel.org/sbt-typelevel/faq.html#what-is-a-base-version-anyway
ThisBuild / tlBaseVersion := "0.0" // your current series x.y

ThisBuild / organization := "com.permutive"
ThisBuild / organizationName := "Permutive"
ThisBuild / startYear := Some(2022)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(
  // your GitHub handle and name
  tlGitHubDev("janstenpickle", "Chris Jansen")
)

// publish to s01.oss.sonatype.org (set to true to publish to oss.sonatype.org instead)
ThisBuild / tlSonatypeUseLegacyHost := false

// publish website from this branch
ThisBuild / tlSitePublishBranch := Some("main")

val Scala213 = "2.13.10"
ThisBuild / crossScalaVersions := Seq("2.12.15", Scala213, "3.2.0")
ThisBuild / scalaVersion := Scala213 // the default Scala

val Prometheus4Cats = "1.0.0-RC3"

lazy val root = tlCrossRootProject.aggregate(catsEffect, trace4Cats)

lazy val catsEffect = project
  .in(file("cats-effect"))
  .settings(
    name := "prometheus4cats-contrib-cats-effect",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "org.typelevel" %% "cats-effect" % "3.3.14"
    ),
    libraryDependencies ++= PartialFunction
      .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
        case Some((2, 12)) =>
          "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1"
      }
      .toList
  )

lazy val trace4Cats = project
  .in(file("trace4cats"))
  .settings(
    name := "prometheus4cats-contrib-trace4cats",
    libraryDependencies ++= Seq(
      "com.permutive" %% "prometheus4cats" % Prometheus4Cats,
      "io.janstenpickle" %% "trace4cats-kernel" % "0.14.0"
    )
  )

lazy val docs = project.in(file("site")).enablePlugins(TypelevelSitePlugin)
