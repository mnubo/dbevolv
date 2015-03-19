package com.mnubo.dbschemas

import java.io.File

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.docker.Docker
import com.typesafe.config.ConfigFactory

object TestDatabaseBuilder extends Logging {
  private val MnuboDockerRegistry = "dockerep-0.mtl.mnubo.com"

  def build(schemaBuildVersion: String) = {
    val config = ConfigFactory.defaultOverrides().withFallback(ConfigFactory.parseFile(new File("db.conf")))
    val dbKind = config.getString("database_kind")
    val db = Database.databases(dbKind)
    val schemaName = config.getString("schema_name")
    val imageName = s"test_$schemaName"
    val repositoryName = s"$MnuboDockerRegistry/$imageName"

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
      config.getString("workstation.create_database_statement").replace("@@DATABASE_NAME@@", schemaName),
      false,
      None
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
}

object BuildTestDb extends App {
  TestDatabaseBuilder.build(args(0))
}