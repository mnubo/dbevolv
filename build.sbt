import sbt.Keys._
import ReleaseKeys._
import sbtrelease._
import ReleaseStateTransformations._

val mnuboNexus = "http://artifactory.mtl.mnubo.com:8081/artifactory"
val mnuboThirdParties = "Mnubo third parties" at s"$mnuboNexus/repo"
val mnuboSnaphots = "Mnubo snapshots" at s"$mnuboNexus/libs-snapshot-local/"
val mnuboReleases = "Mnubo releases" at s"$mnuboNexus/libs-release-local/"

lazy val commonSettings = releaseSettings ++ Seq(
  organization      := "com.mnubo",
  version           := "1.0.0",
  scalacOptions     ++= Seq("-target:jvm-1.7", "-deprecation", "-feature"),
  resolvers         := Seq( // Completely overrides the list of standard repos, since everything is cached in Artifactory (the less resolvers you have, the faster the resolve phase).
    mnuboThirdParties,
    mnuboSnaphots,
    mnuboReleases
  ),
  publishTo         := Some(mnuboReleases),
  credentials       += Credentials("Artifactory Realm", "artifactory.mtl.mnubo.com", "admin", "rootroot"),
  releaseVersion    := identity, // The current version is already the good one
  nextVersion       := { (ver: String) => Version(ver).map(_.bumpMinor.string).getOrElse(versionFormatError) }, // Don't 'snapshot' the version
  releaseProcess    := Seq[ReleaseStep]( // Don't need to commit the release version, since it is already the good one
    checkSnapshotDependencies,
    inquireVersions,
    runTest,
    setReleaseVersion,
    tagRelease,
    publishArtifacts,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publish := { })
  .aggregate(tool, plugin)

lazy val tool = (project in file("tool"))
  .settings(commonSettings: _*)
  .settings(
    name := "dbschemas",
    libraryDependencies ++= Seq(
      "com.datastax.cassandra"  %  "cassandra-driver-core"  % "2.1.4",
      "com.typesafe"            %  "config"                 % "1.2.1"
    )
  )

lazy val plugin = (project in file("plugin"))
  .settings(commonSettings: _*)
  .settings(
    name := "dbschemas-sbt-plugin",
    sbtPlugin := true
  )



