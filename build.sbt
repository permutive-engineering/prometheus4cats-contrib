ThisBuild / scalaVersion           := "2.13.16"
ThisBuild / crossScalaVersions     := Seq("2.13.16", "3.3.6")
ThisBuild / organization           := "com.permutive"
ThisBuild / versionPolicyIntention := Compatibility.BinaryAndSourceCompatible

addCommandAlias("ci-test", "fix --check; versionPolicyCheck; mdoc; publishLocal; +test")
addCommandAlias("ci-docs", "github; mdoc; headerCreateAll")
addCommandAlias("ci-publish", "versionCheck; github; ci-release")

lazy val documentation = project
  .enablePlugins(MdocPlugin)

lazy val `prometheus4cats-contrib-cats-effect` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-cats-effect`)

lazy val `prometheus4cats-contrib-trace4cats` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-trace4cats`)

lazy val `prometheus4cats-contrib-refreshable` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-refreshable`)
  .settings(libraryDependencies ++= scalaVersion.value.on(2)(Dependencies.`kind-projector`))

lazy val `prometheus4cats-contrib-google-cloud-bigtable` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-google-cloud-bigtable`)
  .dependsOn(`prometheus4cats-contrib-opencensus`)

lazy val `prometheus4cats-contrib-opencensus` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-opencensus`)

lazy val `prometheus4cats-contrib-fs2-kafka` = module
  .settings(libraryDependencies ++= Dependencies.`prometheus4cats-contrib-fs2-kafka`)
