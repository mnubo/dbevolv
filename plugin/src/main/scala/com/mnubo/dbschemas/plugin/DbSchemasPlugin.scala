package com.mnubo.dbschemas.plugin

import com.mnubo.dbschemas.TestDatabaseBuilder
import com.typesafe.config.ConfigFactory
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin
import sbtassembly.AssemblyPlugin.autoImport._
import sbtdocker.DockerKeys._
import sbtdocker.immutable.Dockerfile
import sbtdocker.{DockerPlugin, ImageName}
import sbtrelease.ReleasePlugin.ReleaseKeys._
import sbtrelease.ReleasePlugin._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease._

import scala.io.Source

object DbSchemasPlugin extends AutoPlugin {
  private val config = ConfigFactory.parseFile(new File("db.conf"))
  private val schemaName = config.getString("schema_name")
  private val dbschemasVersion = Source.fromInputStream(getClass.getResourceAsStream("/version.txt")).getLines().mkString
  private val mnuboNexus = "http://artifactory.mtl.mnubo.com:8081/artifactory"
  private val mnuboThirdParties = "Mnubo third parties" at s"$mnuboNexus/repo"
  private val mnuboSnaphots = "Mnubo snapshots" at s"$mnuboNexus/libs-snapshot-local/"
  private val mnuboReleases = "Mnubo releases" at s"$mnuboNexus/libs-release-local/"

  override def requires = DockerPlugin && AssemblyPlugin

  object autoImport {
    val buildTestContainer = taskKey[Unit]("Build test database container")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = releaseSettings ++ Seq(
    // Avoid the user to give a name to the SBT project: use the schema name defined in the config.
    name                                  := schemaName,
    organization                          := "com.mnubo",
    resolvers                             := Seq(mnuboReleases, mnuboThirdParties, mnuboSnaphots),
    publishTo                             := Some(mnuboReleases),
    // Specify what is the main class to run in the fat jar
    mainClass in assembly                 := Some("com.mnubo.dbschemas.DbSchemas"),
    // We just need the dbschemas library to build a schema. We automatically infer the version to use.
    libraryDependencies                   += "com.mnubo" %% "dbschemas" % dbschemasVersion,
    // Give the fat jar a simple name
    assemblyJarName                       := s"$schemaName-schema-manager.jar",
    buildTestContainer                    := TestDatabaseBuilder.build(version.value),
    dockerBuildAndPush                    <<= (dockerBuildAndPush dependsOn buildTestContainer),
    dockerfile in docker                  := {
      val artifact = (assembly in assembly).value
      val artifactTargetPath = s"/app/${artifact.name}"

      new Dockerfile {
        // User the space friendlier centOS distrib (compared to default Ubuntu), the less buggy Oracle JRE (compared to OpenJDK), and clean stuff properly. Bottom line: 400MB instead of 815MB.
        from("domblack/oracle-jre8")
        add(artifact, artifactTargetPath)
        addRaw("db.conf", "/app/db.conf")
        addRaw("migrations/", "/app/migrations/")
        workDir("/app")
        entryPoint("java", "-jar", artifactTargetPath)
      }
    },
    imageNames in docker := Seq(
      ImageName(
        namespace = Some("dockerep-0.mtl.mnubo.com"),
        repository = name.value + "-mgr",
        tag = Some(version.value)
      ),
      ImageName(
        namespace = Some("dockerep-0.mtl.mnubo.com"),
        repository = name.value + "-mgr",
        tag = Some("latest")
      )
    ),
    // Auto increment the version every time we run the build in Jenkins by using the sbt-release plugin.
    publishArtifactsAction                := dockerBuildAndPush.value,
    releaseVersion                        := identity, // The current version is already the good one
    nextVersion                           := { (ver: String) => sbtrelease.Version(ver).map(_.bumpBugfix.string).getOrElse(versionFormatError) }, // Don't 'snapshot' the version
    // Don't need to commit the release version, since it is already the good one.
    releaseProcess                        := Seq[ReleaseStep](
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
}
