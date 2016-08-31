package com.mnubo.dbevolv.plugin

import com.typesafe.config.ConfigFactory
import sbt.Attributed._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin
import sbtassembly.AssemblyPlugin.autoImport._
import sbtdocker.DockerKeys._
import sbtdocker.Instructions._
import sbtdocker.immutable.Dockerfile
import sbtdocker.staging.CopyFile
import sbtdocker.{DockerPlugin, ImageName, Instruction}
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease._

import scala.io.Source

object DbevolvPlugin extends AutoPlugin {
  private val config = ConfigFactory.parseFile(new File("db.conf"))
  private val schemaName = config.getString("schema_name")
  private val dockerNamespace = if (config.hasPath("docker_namespace")) Some(config.getString("docker_namespace")) else None
  private val dbevolvVersion = Source.fromInputStream(getClass.getResourceAsStream("/version.txt")).getLines().mkString
  private val dbDependencies = Map(
    "cassandra" -> Seq("com.datastax.cassandra" % "cassandra-driver-core" % "3.0.0"),
    "elasticsearch" -> Seq("com.mnubo" %% "dbevolv-elasticsearch" % dbevolvVersion),
    "elasticsearch2" -> Seq("com.mnubo" %% "dbevolv-elasticsearch2" % dbevolvVersion),
    "mysql" -> Seq("mysql" % "mysql-connector-java" % "5.1.35")
  )

  override def requires = DockerPlugin && AssemblyPlugin && ReleasePlugin

  object autoImport {
    val buildTestContainer = taskKey[Unit]("Build test database container, and test migrations along the way.")
    val buildAndPushTestContainer = taskKey[Unit]("Build test database container, and then push it.")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    // Avoid the user to give a name to the SBT project: use the schema name defined in the config.
    name                                  := schemaName,
    // Specify what is the main class to run in the fat jar
    mainClass in assembly                 := Some("com.mnubo.dbevolv.Dbevolv"),
    // We just need the dbevolv library to build a schema. We automatically infer the version to use.
    libraryDependencies                   ++= Seq(
      "com.mnubo" %% "dbevolv" % dbevolvVersion  excludeAll (
        ExclusionRule("org.joda", "joda-convert"),
        ExclusionRule("org.slf4j", "slf4j-log4j12"),
        ExclusionRule("com.sun.jmx", "jmxri"),
        ExclusionRule("com.sun.jdmk", "jmxtools")
      )
    ) ++ dbDependencies(config.getString("database_kind")),

    assemblyMergeStrategy in assembly     := {
      case "org/joda/time/base/BaseDateTime.class" => MergeStrategy.first
      case "META-INF/io.netty.versions.properties" => MergeStrategy.first
      case x => (assemblyMergeStrategy in assembly).value(x)
    },

    // Give the fat jar a simple name
    assemblyJarName                       := s"$schemaName-schema-manager.jar",
    buildTestContainer                    <<= buildTestContainerTask(doPush = false),
    buildAndPushTestContainer             <<= buildTestContainerTask(doPush = true),
    dockerBuildAndPush                    <<= (dockerBuildAndPush dependsOn buildAndPushTestContainer),
    dockerfile in docker                  := {
      val artifact = (assembly in assembly).value
      val artifactTargetPath = s"/app/${artifact.name}"

      val base = Seq[Instruction](
        From("mnubo/jre8:u91"),
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
    imageNames in docker                  := Seq(
      ImageName(
        namespace = dockerNamespace,
        repository = name.value + "-mgr",
        tag = Some(version.value)
      ),
      ImageName(
        namespace = dockerNamespace,
        repository = name.value + "-mgr",
        tag = Some("latest")
      )
    ),
    // Auto increment the version every time we run the build in Jenkins by using the sbt-release plugin.
    releasePublishArtifactsAction         := {
      dockerBuildAndPush.value

      // Clean ourselves
      streams.value.log.info(s"Cleaning images...")
      (imageNames in docker).value.foreach(img => s"docker rmi -f $img".!)
      streams.value.log.info(s"Images cleaned.")
    },
    releaseVersion                        := identity, // The current version is already the good one
    releaseNextVersion                    := { (ver: String) => sbtrelease.Version(ver).map(_.bumpBugfix.string).getOrElse(versionFormatError) }, // Don't 'snapshot' the version
    // Don't need to commit the release version, since it is already the good one.
    releaseProcess                        := Seq[ReleaseStep](
      setupRemoteTracking,
      inquireVersions,
      runTest,
      setReleaseVersion,
      dockerOnlyPublishArtifacts,
      tagRelease,
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

  import Utilities._
  private lazy val dockerOnlyPublishArtifacts = ReleaseStep(
    action = { st: State =>
      val extracted = st.extract
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(releasePublishArtifactsAction in Global in ref, st)
    },
    enableCrossBuild = true
  )

  private lazy val setupRemoteTracking: ReleaseStep = ReleaseStep(
    action = identity,
    check = { st: State =>
      val cmd = "git checkout -t -B master origin/master"
      st.log.info(cmd)
      cmd.!
      st
    },
    enableCrossBuild = true
  )

  private def buildTestContainerTask(doPush: Boolean) = Def.task[Unit] {
    streams.value.log.info(s"Building a test container. dbevolv version: $dbevolvVersion")
    val cp = (fullClasspath in Compile).value
    val args =
      if (doPush)
        Seq("push")
      else
        Seq()
    val scalaRun = (runner in run).value

    sbt.Defaults.toError(scalaRun.run(
      "com.mnubo.dbevolv.TestDatabaseBuilder",
      data(cp),
      args,
      streams.value.log
    ))

  }
}
