import sbt.Keys._

val mnuboNexus = "http://artifactory.mtl.mnubo.com:8081/artifactory"
val mnuboThirdParties = "Mnubo third parties" at s"$mnuboNexus/repo"
val mnuboSnaphots = "Mnubo snapshots" at s"$mnuboNexus/libs-snapshot-local/"
val mnuboReleases = "Mnubo releases" at s"$mnuboNexus/libs-release-local/"

lazy val commonSettings = Seq(
  organization := "com.mnubo",
  version := "1.0.0",
  scalacOptions ++= Seq("-target:jvm-1.7", "-deprecation", "-feature"),
  resolvers := Seq( // Completely overrides the list of standard repos, since everything is cached in Artifactory (the less resolvers you have, the faster the resolve phase).
    mnuboThirdParties,
    mnuboSnaphots,
    mnuboReleases
  ),
  publishTo := Some(if (version.value.endsWith("-SNAPSHOT")) mnuboSnaphots else mnuboReleases),
  credentials += Credentials("Artifactory Realm", "artifactory.mtl.mnubo.com", "admin", "rootroot")
)

lazy val tool = (project in file("tool")).
  settings(commonSettings: _*).
  settings(
    name := "dbschemas",
    libraryDependencies ++= Seq(
      "com.datastax.cassandra"  %  "cassandra-driver-core"  % "2.1.4",
      "com.typesafe"            %  "config"                 % "1.2.1"
    )
  )

lazy val plugin = (project in file("plugin")).
  settings(commonSettings: _*).
  settings(
    name := "dbschemas-sbt-plugin",
    sbtPlugin := true
  )



