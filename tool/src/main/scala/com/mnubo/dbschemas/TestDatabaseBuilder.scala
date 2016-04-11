package com.mnubo
package dbschemas

import java.io.File

import com.mnubo.app_util.{Logging, MnuboConfiguration}
import com.mnubo.dbschemas.docker.{ContainerInfo, Docker}
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigValueFactory}

import scala.util.control.NonFatal

object TestDatabaseBuilder extends Logging {
  def main(args: Array[String]) = { // Don't ask me why, but a weird bug in Scala compiler prevents me to extend App instead.

    val MnuboDockerRegistry = "dockerep-0.mtl.mnubo.com"
    val doPush = args.contains("push")
    val defaultConfig = ConfigFactory.load(ConfigParseOptions.defaults().setClassLoader(getClass.getClassLoader))
    val mnuboConfig = MnuboConfiguration.loadConfig(
      generalConfig = ConfigFactory
        .parseFile(new File("db.conf"))
        .withFallback(defaultConfig),
      env = "workstation",
      configDirectory = "config"
    )
    val config = mnuboConfig.withValue("force_pull_verification_db", ConfigValueFactory.fromAnyRef(false))
    val dbKind = config.getString("database_kind")
    val dbNameProvider =
      getClass
        .getClassLoader
        .loadClass(config.getString("name_provider_class"))
        .newInstance()
        .asInstanceOf[DatabaseNameProvider]

    val schemaName = config.getString("schema_name")
    val dbSchemaName = dbNameProvider.computeDatabaseName(schemaName, None)
    val maybeSandboxNameProvider = dbNameProvider match {
      case x:ZoneAwareDatabaseNameProvider=> Some(ZoneAwareDatabaseNameProvider.forSandbox())
      case _ => None
    }

    val hasInstanceForEachNamespace = config.getBoolean("hasInstanceForEachNamespace")
    val imageName = s"test-$schemaName"
    val repositoryName = s"$MnuboDockerRegistry/$imageName"
    val db = Database.databases(dbKind)

    log.info(s"Starting a fresh test $dbKind $schemaName instance ...")
    val container = Docker.run(db.testDockerBaseImage)

    try {
      val availableMigrations = DatabaseMigrator.getAvailableMigrations

      log.info(s"Creating and migrating test database '$dbSchemaName' to latest version ...")
      val images = availableMigrations.map { schemaVersion =>
        if (schemaVersion.isRebase && schemaVersion != availableMigrations.head) {
          // Verify the rebase before we migrate the ref db to the latest version
          log.info(s"Verifying rebase script ${schemaVersion.version}")
          val fromRebaseContainer = Docker.run(db.testDockerBaseImage)

          try {
            // Execute rebase script on new db
            if(schemaVersion == availableMigrations.last) {
              log.info("Migrating to last version")
              migrate(null, twice = false, container = fromRebaseContainer)
            } else {
              log.info("Migrating to next version")
              migrate(schemaVersion.version, twice = false, container = fromRebaseContainer)
            }

            // Verify schemas are compatible
            withConnection(fromRebaseContainer) {
              fromConnection =>
                fromConnection.setActiveSchema(dbSchemaName)

                withConnection(container) {
                  connection =>
                    connection.setActiveSchema(dbSchemaName)
                    if (!connection.isSameSchema(fromConnection))
                      throw new Exception(s"Rebase script for version ${schemaVersion.version} is not compatible with previous version schema")
                }
            }
          }
          finally {
            Docker.stop(fromRebaseContainer.id)
            Docker.remove(fromRebaseContainer.id)
          }
        }

        migrate(schemaVersion.version, twice = true)
        // Migrate sandbox if required
        maybeSandboxNameProvider.foreach{
          sandboxNameProvider =>
            migrate(schemaVersion.version, twice = true, maybeOtherSchemaName = Some(sandboxNameProvider.computeDatabaseName(schemaName, None)))
        }
        if(hasInstanceForEachNamespace){
          Seq("cars", "printers", "cows").foreach { namespace =>
            migrateNamespace(schemaVersion.version, namespace)
            // Migrate sandbox if required
            maybeSandboxNameProvider.foreach {
              sandboxNameProvider =>
                migrateNamespace(schemaVersion.version, namespace, maybeOtherSchemaName = Some(sandboxNameProvider.computeDatabaseName(schemaName, None)))
            }
          }
        }

        log.info(s"Commiting $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version}...")
        db.testDockerBaseImage.flushCmd.foreach { cmd =>
          Docker.exec(container.id, cmd)
        }
        val imageId = Docker.commit(container.id, repositoryName, schemaVersion.version)

        if (doPush) {
          log.info(s"Publishing $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version} ...")
          Docker.push(s"$repositoryName:${schemaVersion.version}")
        }

        imageId
      }

      if (doPush) {
        log.info(s"Tagging and pushing latest version...")
        val imageId = images.last
        Docker.execShell(s"docker tag -f $imageId $repositoryName:latest")
        Docker.push(s"$repositoryName:latest")
      }

      log.info(s"Testing rollback procedure...")
      val firstVersion = availableMigrations.head
      migrate(firstVersion.version, twice = false)

      if (doPush) {
        images.foreach { imageId =>
          log.info(s"Cleaning up image $imageId ...")
          Docker.removeImage(imageId)
        }
      }
    }
    catch {
      case NonFatal(ex) =>
        log.error(s"Test database build failed", ex)
        throw ex
    }
    finally {
      log.info(s"Cleaning up container ${container.id} ...")
      Docker.stop(container.id)
      Docker.remove(container.id)
    }

    def migrateNamespace(toVersion: String, namespace:String, container: ContainerInfo = container, maybeOtherSchemaName:Option[String] = None) = {
      withConnection(container) { connection =>
        log.info(s"Connected to $container.")
        val args = DbSchemasArgsConfig(false)

        val maybeConfig = maybeOtherSchemaName.map(name => ConfigFactory.parseString(s"schema_name = $name").withFallback(config))
        DatabaseMigrator.migrate(DbMigrationConfig(
          connection,
          args,
          maybeConfig.getOrElse(config),
          Option(namespace),
          Option(toVersion)
        ))
      }
    }

    def migrate(toVersion: String, twice: Boolean, container: ContainerInfo = container, maybeOtherSchemaName:Option[String] = None) = {
      withConnection(container) { connection =>
        log.info(s"Connected to $container.")
        DatabaseMigrator.migrate(DbMigrationConfig(
          connection,
          maybeOtherSchemaName.getOrElse(dbSchemaName),
          drop = false,
          Option(toVersion),
          skipSchemaVerification = true,
          applyUpgradesTwice = twice,
          config
        ))
      }
    }

    def withConnection(container: ContainerInfo)(action: DatabaseConnection => Unit) =
      using(db.openConnection(
        dbSchemaName,
        Docker.dockerHost,
        container.realPort,
        db.testDockerBaseImage.username,
        db.testDockerBaseImage.password,
        config.getString("create_database_statement"),
        config
      ))(action)
  }
}
