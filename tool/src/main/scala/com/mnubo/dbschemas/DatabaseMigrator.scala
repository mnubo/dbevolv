package com.mnubo.dbschemas

import java.io.{FileFilter, File}

import scala.io.Source

object DatabaseMigrator {
  def migrate(db: Database, host: String, port: Int, userName: String, pwd: String, schema: String, targetVersion: Option[String]): Unit = {
    val connection = db.openConnection(host, port, userName, pwd, schema)
    try {
      migrate(connection, targetVersion)
    }
    finally {
      connection.close()
    }
  }

  private def migrate(connection: DatabaseConnection, targetVersion: Option[String]): Unit = {
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
      downgrade(connection, availableMigrations.slice(targetIndex + 1, currentIndex).reverse)
    else if (currentIndex < targetIndex)
      upgrade(connection, availableMigrations.slice(currentIndex + 1, targetIndex))
    else
      () // Nothing to do, already at the right target version
  }

  private def upgrade(connection: DatabaseConnection, steps: Seq[String]) = {
    for {
      step <- steps
      stmtFile = findStatementFile(step, "upgrade.")
      stmts = getStatements(stmtFile)
    } {
      stmts.foreach(connection.execute)
      connection.markMigrationAsInstalled(step)
    }
  }

  private def downgrade(connection: DatabaseConnection, steps: Seq[String]) = {
    for {
      step <- steps
      stmtFile = findStatementFile(step, "downgrade.")
      stmts = getStatements(stmtFile)
    } {
      stmts.foreach(connection.execute)
      connection.markMigrationAsUninstalled(step)
    }
  }

  private def getStatements(stmtFile: File) =
    Source
      .fromFile(stmtFile)
      .getLines()
      .filterNot(_.isEmpty)
      .filterNot(_.startsWith("#"))
      .mkString(" ")
      .split(";")

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
}
