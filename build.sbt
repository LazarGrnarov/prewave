ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .settings(
    name := "TestTask"
  )

libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.10.3"
libraryDependencies += "com.softwaremill.sttp.client3" %% "circe" % "3.10.3"

libraryDependencies ++= Seq(
  "org.apache.logging.log4j" % "log4j-api" % "2.24.3",
  "org.apache.logging.log4j" % "log4j-core" % "2.24.3"
)

val circeVersion = "0.14.11"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser")
  .map(_ % circeVersion)