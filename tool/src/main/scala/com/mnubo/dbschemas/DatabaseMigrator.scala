package com.mnubo
package dbschemas

import java.io.{FileFilter, File}

import com.mnubo.app_util.Logging

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
    val installedMigrations = ctx.connection.getInstalledMigrationVersions.map(_.version)
    val target = ctx.targetVersion.getOrElse(availableMigrations.last)

    val currentIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => !installedMigrations.contains(v) }
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

  private def upgrade(ctx: MigrationContext, steps: Seq[String]) = {
    import ctx._

    if (!skipSchemaVerification && !connection.isSchemaValid)
      throw new Exception(s"Oops, it seems the target schema of $name is not what it should be... Please call your dearest developer for helping de-corrupting the database.")

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = findStatementFile(step, "upgrade.")
      stmts = parseStatements(stmtFile)
    } {
      logInfo(s"Executing upgrade $step")
      stmts.foreach(_.execute(connection, name))

      if (applyUpgradesTwice) {
        logInfo(s"Checking upgrade $step is idempotent")
        stmts.foreach(_.execute(connection, name))
      }
      connection.markMigrationAsInstalled(step)
    }
  }

  private def downgrade(ctx: MigrationContext, steps: Seq[String]) = {
    import ctx._

    log.info("Will apply the following migrations: " + steps.mkString(", "))

    for {
      step <- steps
      stmtFile = findStatementFile(step, "downgrade.")
      stmts = parseStatements(stmtFile)
    } {
      stmts.foreach(_.execute(connection, name))
      connection.markMigrationAsUninstalled(step)
    }
  }

  private def parseStatements(stmtFile: File) =
    Source
      .fromFile(stmtFile)
      .getLines()
      .filterNot(_.trim.isEmpty)
      .filterNot(_.startsWith("#"))
      .mkString(" ")
      .split(";")
      .filterNot(_.isEmpty) // mkString is at least producing an empty string, so have to refilter empty lines.
      .map { line =>
        if (line.startsWith("@@"))
          ClassStatement(line.replace("@@", ""))
        else
          StringStatement(line)
      }

  private def findStatementFile(step: String, stmtType: String) =
    new File(s"migrations/$step")
      .listFiles(new FileFilter {
        override def accept(pathname: File) =
          pathname.isFile && !pathname.isHidden
      })
      .find(_.getName.startsWith(stmtType))
      .get

  private def getAvailableMigrations: Seq[String] =
    new File("migrations")
      .listFiles(new FileFilter {
        override def accept(pathname: File) =
          pathname.isDirectory && !pathname.isHidden
      })
      .map(_.getName)
      .sorted

  private sealed trait Statement {
    def execute(conn: DatabaseConnection, databaseName: String)
  }

  private case class StringStatement(statementText: String) extends Statement {
    override def execute(conn: DatabaseConnection, databaseName: String) =
      conn.execute(statementText)
  }

  private case class ClassStatement(className: String) extends Statement {
    private val c = getClass.getClassLoader.loadClass(className)
    private val scripInstance = c.newInstance()
    private val executeMethod = c.getMethods.find(_.getName == "execute").get

    override def execute(conn: DatabaseConnection, databaseName: String) =
      executeMethod.invoke(scripInstance, conn.innerConnection, databaseName)
  }
}

case class MigrationReport(startingVersion: String, migratedToVersion: String)