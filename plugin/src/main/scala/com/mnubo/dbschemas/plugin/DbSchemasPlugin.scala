package com.mnubo.dbschemas.plugin

import com.typesafe.config.ConfigFactory
import sbt.Attributed._
import sbt.Defaults._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin
import sbtassembly.AssemblyPlugin.autoImport._
import sbtdocker.DockerKeys._
import sbtdocker.Instructions._
import sbtdocker.immutable.Dockerfile
import sbtdocker.staging.CopyFile
import sbtdocker.{Instruction, DockerPlugin, ImageName}
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
  private val dbDependencies = Map(
    "cassandra" -> Seq.empty[ModuleID],
    "elasticsearch" -> Seq("org.elasticsearch" % "elasticsearch" % "1.5.2"),
    "mysql" -> Seq("mysql" % "mysql-connector-java" % "5.1.35")
  )


  override def requires = DockerPlugin && AssemblyPlugin

  object autoImport {
    val buildTestContainer = taskKey[Unit]("Build test database container")
    val buildAndPushTestContainer = taskKey[Unit]("Build test database container, and then push it")
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
    libraryDependencies                   ++= Seq(
      "com.mnubo" %% "dbschemas" % dbschemasVersion  excludeAll (
        ExclusionRule("org.joda", "joda-convert"),
        ExclusionRule("org.slf4j", "slf4j-log4j12"),
        ExclusionRule("com.sun.jmx", "jmxri"),
        ExclusionRule("com.sun.jdmk", "jmxtools")
        )
    ) ++ dbDependencies(config.getString("database_kind")),
    // Give the fat jar a simple name
    assemblyJarName                       := s"$schemaName-schema-manager.jar",
    buildTestContainer                    <<= buildTestContainerTask(doPush = false),
    buildAndPushTestContainer             <<= buildTestContainerTask(doPush = true),
    dockerBuildAndPush                    <<= (dockerBuildAndPush dependsOn buildAndPushTestContainer),
    dockerfile in docker                  := {
      val artifact = (assembly in assembly).value
      val artifactTargetPath = s"/app/${artifact.name}"

      val base = Seq[Instruction](
        From("domblack/oracle-jre8"),
        Add(CopyFile(artifact), artifactTargetPath),
        Add(CopyFile(new File("db.conf")), "/app/db.conf"),
        Add(CopyFile(new File("migrations")), "/app/migrations/"),
        WorkDir("/app"),
        EntryPoint.exec(Seq("java", "-jar", artifactTargetPath))
      )

      Dockerfile(
        if (new File("src").exists())
          base :+ Add(CopyFile(new File("src")), "/app/src/")
        else
          base
      )
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
    publishArtifactsAction                := {
      dockerBuildAndPush.value

      // Clean ourselves
      streams.value.log.info(s"Cleaning images...")
      (imageNames in docker).value.foreach(img => s"docker rmi -f $img".!)
      streams.value.log.info(s"Images cleaned.")
    },
    releaseVersion                        := identity, // The current version is already the good one
    nextVersion                           := { (ver: String) => sbtrelease.Version(ver).map(_.bumpBugfix.string).getOrElse(versionFormatError) }, // Don't 'snapshot' the version
    // Don't need to commit the release version, since it is already the good one.
    releaseProcess                        := Seq[ReleaseStep](
      inquireVersions,
      runTest,
      setReleaseVersion,
      publishArtifacts,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

  private def buildTestContainerTask(doPush: Boolean) = Def.task[Unit] {
    streams.value.log.info(s"Building a test container. dbschemas version: $dbschemasVersion")
    val cp = (fullClasspath in Compile).value
    val args =
      if (doPush)
        Seq("push")
      else
        Seq()
    val scalaRun = (runner in run).value

    sbt.Defaults.toError(scalaRun.run(
      "com.mnubo.dbschemas.TestDatabaseBuilder",
      data(cp),
      args,
      streams.value.log
    ))

  }
}
