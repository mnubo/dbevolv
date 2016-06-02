package com.mnubo
package dbevolv

import java.io.{FileFilter, File}

import com.mnubo.app_util.Logging
import com.mnubo.dbevolv.util.MD5

object DatabaseMigrator extends Logging {
  private case class MigrationContext(connection: DatabaseConnection,
                                      name: String,
                                      targetVersion: Option[String],
                                      skipSchemaVerification: Boolean,
                                      applyUpgradesTwice: Boolean)

  def migrate(config: DbMigrationConfig): MigrationReport = {
    import config._

    log.info(s"Will upgrade $name to ${version.getOrElse("latest")} version.")

    if (drop) config.connection.dropDatabase()
    migrate(MigrationContext(config.connection, name, version, skipSchemaVerification, applyUpgradesTwice))
  }

  /**
   * Whether the given list of installed migration makes sense.
   * For example, with the following available migrations:
   *
   * 001
   * 002
   * 003
   * 004 (rebase)
   * 005
   * 006 (rebase)
   * 007
   * 008
   *
   * The following installed versions are valid, because they either start from the beginning, or from a rebase:
   *
   * (001)
   * (001, 002, 003)
   * (004, 005)
   * (006, 007)
   * (006, 007, 008)
   *
   * The following installed version are invalid because they don't start from either the beginning or from a rebase:
   *
   * (002, 003)
   * (003)
   * (007, 008)
   *
   * The following installed version are invalid because they have 'skipped' some migrations:
   *
   * (001, 003)
   * (006, 008)
   */
  private def isInstalledMigrationsSequenceCorrupted(availableMigrations: Seq[Migration], installedMigrationVersions: Seq[String]) = {
    def startsFromRebases(migs: Seq[Migration], acc: Set[Seq[Migration]]): Set[Seq[Migration]] = {
      val remaining = migs.dropWhile(!_.isRebase)

      if (remaining.isEmpty)
        acc
      else
        startsFromRebases(remaining.drop(1), acc + remaining)
    }

    // Compute all valid installed version starts
    val possibleStarts = startsFromRebases(availableMigrations.drop(1), Set(availableMigrations))

    // From a rebase, all installed migrations must correspond to the available sequence.
    !possibleStarts.exists(_.map(_.version).startsWith(installedMigrationVersions))
  }

  private def migrate(ctx: MigrationContext): MigrationReport = {
    val availableMigrations = getAvailableMigrations
    val installedMigrations = ctx.connection.getInstalledMigrationVersions
    val installedVersions = installedMigrations.map(_.version).toSeq.sorted

    log.info(s"available migrations: ${availableMigrations.mkString(", ")}")
    log.info(s"installed migrations: ${if (installedVersions.isEmpty) "None" else installedVersions.mkString(", ")}")

    if (isInstalledMigrationsSequenceCorrupted(availableMigrations, installedVersions))
      throw new Exception("CRITICAL: the migrations installed are not the expected ones.")

    validateInstalledMigrationsChecksums(installedMigrations)

    val migrationSpec = getMigrationToApply(ctx.targetVersion, availableMigrations, installedMigrations)

    val downwardCtx = if(migrationSpec.skipSchemaVerification){
      ctx.copy(skipSchemaVerification = true) // Don't need to validate an empty schema if the database is new
    } else {
      ctx
    }

    migrationSpec.op match {
      case UpgradeOperation =>
        upgrade(downwardCtx, migrationSpec.steps, installedMigrations)
      case DowngradeOperation =>
        downgrade(downwardCtx, migrationSpec.steps)
      case NoopOperation => ()
    }

    MigrationReport(migrationSpec.startingVersion, migrationSpec.migratedToVersion)
  }

  private def validateInstalledMigrationsChecksums(installedMigrations: Set[InstalledVersion]) = {
    for {
      InstalledVersion(version, _, checksum) <- installedMigrations.toSeq.sortBy(_.version)
      if checksum != null && !checksum.isEmpty
      currentChecksum = MD5.forStatementFile(version, Set("upgrade.", "rebase."))
    } {
      if (checksum != currentChecksum)
        throw new Exception(s"Schema manager migration $version has been tampered with. Installed checksum: $checksum. Schema manager checksum: $currentChecksum")
    }
  }

  private def upgrade(ctx: MigrationContext, steps: Seq[Migration], installedMigrations: Set[InstalledVersion]) = {
    import ctx._

    if (!skipSchemaVerification && !connection.isSchemaValid)
      throw new Exception(s"Oops, it seems the target schema of $name is not what it should be... Please call your dearest developer for helping de-corrupting the database.")

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = step.findStatementFile(Set("upgrade.", "rebase."))
      stmts = StatementFiles.parseStatements(stmtFile)
    } {
      if (!step.isRebase || installedMigrations.isEmpty) {
        log.info(s"Executing upgrade $step")
        stmts.foreach(_.execute(connection, name))

        if (applyUpgradesTwice) {
          log.info(s"Checking upgrade $step is idempotent")
          stmts.foreach(_.execute(connection, name))
        }
      }

      connection.markMigrationAsInstalled(step.version, MD5.forStatements(stmtFile, stmts), step.isRebase)
    }
  }

  private def downgrade(ctx: MigrationContext, steps: Seq[Migration]) = {
    import ctx._

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = step.findStatementFile(Set("downgrade."))
      stmts = StatementFiles.parseStatements(stmtFile)
    } {
      stmts.foreach(_.execute(connection, name))
      connection.markMigrationAsUninstalled(step.version)
    }
  }

  def getAvailableMigrations: Seq[Migration] =
    new File("migrations")
      .listFiles(new FileFilter {
        override def accept(pathname: File) =
          pathname.isDirectory && !pathname.isHidden
      })
      .map(f => Migration(f.getName, f.list().exists(_.startsWith("rebase."))))
      .sortBy(_.version)

  def getMigrationToApply(maybeTargetVersion:Option[String], availableMigrations:Seq[Migration], installedMigrations:Set[InstalledVersion]) : MigrationSpec = {
    val installedVersions = installedMigrations.map(_.version).toSeq.sorted

    val targetVersion = maybeTargetVersion.getOrElse(availableMigrations.last.version)

    val lastInstalledMigrationIndex =
      availableMigrations.size -
        availableMigrations.map(_.version).reverse.takeWhile(!installedVersions.contains(_)).size -
        1

    val skipSchemaVerification =
      if (lastInstalledMigrationIndex >= 0) {
        log.info(s"Current version is ${availableMigrations(lastInstalledMigrationIndex)}. Will upgrade to $targetVersion.")
        false
      }
      else {
        log.info(s"This is a brand new database, with no version yet installed. Will upgrade to $targetVersion.")
        true // Don't need to validate an empty schema if the database is new
      }

    val targetIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => targetVersion == v.version }
        .get
        ._2

    val startingVersion = if (lastInstalledMigrationIndex < 0) availableMigrations.head.version else availableMigrations(lastInstalledMigrationIndex).version
    val (operation, steps) = if(lastInstalledMigrationIndex > targetIndex) {
      val stepsToExecute = availableMigrations
        .slice(targetIndex + 1, lastInstalledMigrationIndex + 1)
        .reverse
        .takeWhile(!_.isRebase) // Stop at last rebase. It is not supported to rollback through a rebase.

      (DowngradeOperation, stepsToExecute)
    } else if (lastInstalledMigrationIndex < targetIndex) {
      val startingIndex =
        if (lastInstalledMigrationIndex == -1)
          availableMigrations.zipWithIndex.filter{case (mig, idx) => mig.isRebase && idx <= targetIndex}.map(_._2).lastOption.getOrElse(0)
        else
          lastInstalledMigrationIndex + 1 // Existing database, execute everything from current index

      val stepsToExecute = availableMigrations
        .slice(startingIndex, targetIndex + 1)

      (UpgradeOperation, stepsToExecute)
    } else {
      (NoopOperation, Seq.empty)
    }
    log.debug(s"$availableMigrations")
    log.debug(s"Last index $lastInstalledMigrationIndex, Target $targetIndex")
    MigrationSpec(startingVersion, targetVersion, skipSchemaVerification, steps, operation)
  }
}

sealed trait MigrationOperation
object UpgradeOperation extends MigrationOperation
object DowngradeOperation extends MigrationOperation
object NoopOperation extends MigrationOperation

case class MigrationSpec(startingVersion: String, migratedToVersion: String, skipSchemaVerification:Boolean, steps: Seq[Migration], op:MigrationOperation)

case class MigrationReport(startingVersion: String, migratedToVersion: String)

case class Migration(version: String, isRebase: Boolean) {
  def findStatementFile(stmtType: Set[String]) =
    StatementFiles.findStatementFile(version, stmtType)

  override def toString =
    version + (if (isRebase) " (rebase)" else "")
}