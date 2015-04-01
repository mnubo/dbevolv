package com.mnubo
package dbschemas

import java.io.{FileFilter, File}

import com.mnubo.app_util.Logging

import scala.io.Source

object DatabaseMigrator extends Logging {
  def migrate(config: DbMigrationConfig): Unit = {
    import config._

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
      migrate(connection, name, version)
    }
  }

  private def migrate(connection: DatabaseConnection, name: String, targetVersion: Option[String]): Unit = {
    val availableMigrations = getAvailableMigrations
    val installedMigrations = connection.getInstalledMigrationVersions
    val target = targetVersion.getOrElse(availableMigrations.last)

    val currentIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => !installedMigrations.contains(v) }
        .get
        ._2 - 1

    val targetIndex =
      availableMigrations
        .zipWithIndex
        .find { case (v, i) => target == v }
        .get
        ._2

    if (currentIndex > targetIndex)
      downgrade(connection, name, availableMigrations.slice(targetIndex + 1, currentIndex + 1).reverse)
    else if (currentIndex < targetIndex)
      upgrade(connection, name, availableMigrations.slice(currentIndex + 1, targetIndex + 1))
    else
      () // Nothing to do, already at the right target version
  }

  private def upgrade(connection: DatabaseConnection, name: String, steps: Seq[String]) = {
    for {
      step <- steps
      stmtFile = findStatementFile(step, "upgrade.")
      stmts = parseStatements(stmtFile)
    } {
      logInfo(s"Executing upgrade $step")
      stmts.foreach(_.execute(connection, name))
      connection.markMigrationAsInstalled(step)
    }
  }

  private def downgrade(connection: DatabaseConnection, name: String, steps: Seq[String]) = {
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
