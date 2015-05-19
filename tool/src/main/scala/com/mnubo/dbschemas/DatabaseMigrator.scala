package com.mnubo
package dbschemas

import java.io.{FileFilter, File}

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.util.MD5

import scala.io.Source

object DatabaseMigrator extends Logging {
  private case class MigrationContext(connection: DatabaseConnection,
                                      name: String,
                                      targetVersion: Option[String],
                                      skipSchemaVerification: Boolean,
                                      applyUpgradesTwice: Boolean)

  def migrate(config: DbMigrationConfig): MigrationReport = {
    import config._

    log.info(s"Will upgrade $name @ $host to ${version.getOrElse("latest")} version.")

    using(db.openConnection(
      schemaName,
      host,
      port,
      username,
      password,
      name,
      createDatabaseStatement,
      wholeConfig
    )) { connection =>
      if (drop) connection.dropDatabase
      migrate(MigrationContext(connection, name, version, skipSchemaVerification, applyUpgradesTwice))
    }
  }

  private def migrate(ctx: MigrationContext): MigrationReport = {
    val availableMigrations = getAvailableMigrations
    val installedMigrations = ctx.connection.getInstalledMigrationVersions
    validateInstalledMigrations(installedMigrations)

    val installedMigrationVersions = installedMigrations.map(_.version)
    val target = ctx.targetVersion.getOrElse(availableMigrations.last)

    val currentIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => !installedMigrationVersions.contains(v) }
        .map(_._2)
        .getOrElse(availableMigrations.size) - 1

    if (currentIndex > 0)
      log.info(s"Current version is ${availableMigrations(currentIndex)}.")
    else
      log.info(s"This is a brand new database, with no version yet installed.")

    val targetIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => target == v }
        .get
        ._2

    if (currentIndex > targetIndex)
      downgrade(ctx, availableMigrations.slice(targetIndex + 1, currentIndex + 1).reverse)
    else if (currentIndex < targetIndex)
      upgrade(ctx, availableMigrations.slice(currentIndex + 1, targetIndex + 1))
    else
      () // Nothing to do, already at the right target version

    MigrationReport(if (currentIndex < 0) availableMigrations.head else availableMigrations(currentIndex), target)
  }

  private def validateInstalledMigrations(installedMigrations: Set[InstalledVersion]) = {
    for {
      InstalledVersion(version, _, checksum) <- installedMigrations.toSeq.sortBy(_.version)
      if checksum != null && !checksum.isEmpty
      currentChecksum = MD5.forStatementFile(version, "upgrade.")
    } {
      if (checksum != currentChecksum)
        throw new Exception(s"Schema manager migration $version has been tampered with. Installed checksum: $checksum. Schema manager checksum: $currentChecksum")
    }
  }

  private def upgrade(ctx: MigrationContext, steps: Seq[String]) = {
    import ctx._

    if (!skipSchemaVerification && !connection.isSchemaValid)
      throw new Exception(s"Oops, it seems the target schema of $name is not what it should be... Please call your dearest developer for helping de-corrupting the database.")

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = StatementFiles.findStatementFile(step, "upgrade.")
      stmts = StatementFiles.parseStatements(stmtFile)
    } {
      log.info(s"Executing upgrade $step")
      stmts.foreach(_.execute(connection, name))

      if (applyUpgradesTwice) {
        log.info(s"Checking upgrade $step is idempotent")
        stmts.foreach(_.execute(connection, name))
      }
      connection.markMigrationAsInstalled(step, MD5.forStatements(stmtFile, stmts))
    }
  }

  private def downgrade(ctx: MigrationContext, steps: Seq[String]) = {
    import ctx._

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = StatementFiles.findStatementFile(step, "downgrade.")
      stmts = StatementFiles.parseStatements(stmtFile)
    } {
      stmts.foreach(_.execute(connection, name))
      connection.markMigrationAsUninstalled(step)
    }
  }

  def getAvailableMigrations: Seq[String] =
    new File("migrations")
      .listFiles(new FileFilter {
        override def accept(pathname: File) =
          pathname.isDirectory && !pathname.isHidden
      })
      .map(_.getName)
      .sorted
}

case class MigrationReport(startingVersion: String, migratedToVersion: String)