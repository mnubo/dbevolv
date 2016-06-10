package com.mnubo
package dbevolv

import java.io.File

import com.mnubo.app_util.{Logging, MnuboConfiguration}
import com.mnubo.dbevolv.docker.{Container, Docker}
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigValueFactory}

import scala.util.control.NonFatal

object TestDatabaseBuilder extends Logging {
  def main(args: Array[String]) = { // Don't ask me why, but a weird bug in Scala compiler prevents me to extend App instead.
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
    val dockerNamespace = if (config.hasPath("docker_namespace")) Some(config.getString("docker_namespace")) else None
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

    val hasInstanceForEachTenant = config.getBoolean("has_instance_for_each_tenant")
    val imageName = s"test-$schemaName"
    val repositoryName = dockerNamespace.map(ns => s"$ns/$imageName").getOrElse(imageName)
    val db = Database.databases(dbKind)

    log.info(s"Starting a fresh test $dbKind $schemaName instance ...")
    val container = new Container(db.testDockerBaseImage)

    try {
      val availableMigrations = DatabaseMigrator.getAvailableMigrations

      log.info(s"Creating and migrating test database '$dbSchemaName' to latest version ...")
      val images = availableMigrations.map { schemaVersion =>
        if (schemaVersion.isRebase && schemaVersion != availableMigrations.head) {
          // Verify the rebase before we migrate the ref db to the latest version
          log.info(s"Verifying rebase script ${schemaVersion.version}")
          val fromRebaseContainer = new Container(db.testDockerBaseImage)

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
            withConnection(fromRebaseContainer) { fromConnection =>
              fromConnection.setActiveSchema(dbSchemaName)

              withConnection(container) { connection =>
                connection.setActiveSchema(dbSchemaName)
                if (!connection.isSameSchema(fromConnection))
                  throw new Exception(s"Rebase script for version ${schemaVersion.version} is not compatible with previous version schema")
              }
            }
          }
          finally {
            try fromRebaseContainer.stop()
            finally fromRebaseContainer.remove()
          }
        }

        migrate(schemaVersion.version, twice = true)
        // Migrate sandbox if required
        maybeSandboxNameProvider.foreach { sandboxNameProvider =>
          migrate(schemaVersion.version, twice = true, maybeOtherSchemaName = Some(sandboxNameProvider.computeDatabaseName(schemaName, None)))
        }

        if(hasInstanceForEachTenant){
          Seq("cars", "printers", "cows").foreach { tenant =>
            migrateTenant(schemaVersion.version, tenant)
            // Migrate sandbox if required
            maybeSandboxNameProvider.foreach { sandboxNameProvider =>
              migrateTenant(schemaVersion.version, tenant, maybeOtherSchemaName = Some(sandboxNameProvider.computeDatabaseName(schemaName, None)))
            }
          }
        }

        log.info(s"Commiting $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version}...")
        db.testDockerBaseImage.flushCmd.foreach { cmd =>
          container.exec(cmd)
        }
        val imageId = container.commit(repositoryName, schemaVersion.version)

        if (doPush) {
          log.info(s"Publishing $dbKind $schemaName test instance to $repositoryName:${schemaVersion.version} ...")
          container.push(s"$repositoryName:${schemaVersion.version}")
        }

        imageId
      }

      if (doPush) {
        log.info(s"Tagging and pushing latest version...")
        container.tag(s"$repositoryName:latest")
        container.push(s"$repositoryName:latest")
      }

      log.info(s"Testing rollback procedure...")
      val firstVersion = availableMigrations.head
      migrate(firstVersion.version, twice = false)

      if (doPush) {
        images.foreach { imageId =>
          log.info(s"Cleaning up image $imageId ...")
          container.removeImage(imageId)
        }
      }
    }
    catch {
      case NonFatal(ex) =>
        log.error(s"Test database build failed", ex)
        throw ex
    }
    finally {
      log.info(s"Cleaning up container ${container.containerId} ...")
      container.stop()
      container.remove()
    }

    def migrateTenant(toVersion: String, tenant:String, container: Container = container, maybeOtherSchemaName:Option[String] = None) = {
      withConnection(container) { connection =>
        log.info(s"Connected to $container.")
        val args = DbevolvArgsConfig(drop = false)

        val maybeConfig = maybeOtherSchemaName.map(name => ConfigFactory.parseString(s"schema_name = $name").withFallback(config))
        DatabaseMigrator.migrate(DbMigrationConfig(
          connection,
          args,
          maybeConfig.getOrElse(config),
          Option(tenant),
          Option(toVersion)
        ))
      }
    }

    def migrate(toVersion: String, twice: Boolean, container: Container = container, maybeOtherSchemaName:Option[String] = None) = {
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

    def withConnection(container: Container)(action: DatabaseConnection => Unit) =
      using(db.openConnection(
        dbSchemaName,
        container.containerHost,
        container.exposedPort,
        db.testDockerBaseImage.username,
        db.testDockerBaseImage.password,
        config.getString("create_database_statement"),
        config
      ))(action)
  }
}
