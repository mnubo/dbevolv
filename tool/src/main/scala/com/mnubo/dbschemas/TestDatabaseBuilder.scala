package com.mnubo
package dbschemas

import java.io.File

import com.mnubo.app_util.{MnuboConfiguration, Logging}
import com.mnubo.dbschemas.docker.Docker
import com.typesafe.config.{ConfigParseOptions, ConfigFactory}

object TestDatabaseBuilder extends App with Logging {
  val MnuboDockerRegistry = "dockerep-0.mtl.mnubo.com"
  val schemaBuildVersion = args(0)
  val defaultConfig = ConfigFactory.load(ConfigParseOptions.defaults().setClassLoader(getClass.getClassLoader))
  val config = MnuboConfiguration.loadConfig(
    ConfigFactory
      .parseFile(new File("db.conf"))
      .withFallback(defaultConfig),
    "workstation")
  val dbKind = config.getString("database_kind")
  val schemaName = config.getString("schema_name")
  val imageName = s"test-$schemaName"
  val repositoryName = s"$MnuboDockerRegistry/$imageName"
  val db = Database.databases(dbKind)

  logInfo(s"Starting a fresh test $dbKind $schemaName instance...")
  val container = Docker.run(
    dockerImage = db.testDockerBaseImage.name,
    exposedPort = db.testDockerBaseImage.mappedPort,
    isStarted = db.isStarted
  )

  logInfo(s"Creating and migrating test database '$schemaName' to latest version...")
  DatabaseMigrator.migrate(DbMigrationConfig(
    db,
    schemaName,
    Docker.dockerHost,
    container.realPort,
    db.testDockerBaseImage.username,
    db.testDockerBaseImage.password,
    schemaName,
    config.getString("create_database_statement").replace("@@DATABASE_NAME@@", schemaName),
    false,
    None,
    config
  ))

  logInfo(s"Commiting $dbKind $schemaName test instance...")
  Docker.stop(container.id)
  val imageId = Docker.commit(container.id, repositoryName, schemaBuildVersion)

  logInfo(s"Publishing $dbKind $schemaName test instance...")
  Docker.push(repositoryName)

  logInfo(s"Cleaning up...")
  Docker.remove(container.id)
  Docker.removeImage(imageId)
}
