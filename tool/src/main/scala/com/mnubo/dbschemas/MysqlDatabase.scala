package com.mnubo.dbschemas

import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

import com.typesafe.config.Config
import org.joda.time.{DateTime, DateTimeZone}

import scala.annotation.tailrec
import scala.util.control.NonFatal

object MysqlDatabase extends Database {
  val name = "mysql"

  override def openConnection(schemaName: String,
                              host: String,
                              port: Int,
                              userName: String,
                              pwd: String,
                              database: String,
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new MysqlConnection(schemaName, host, if (port > 0) port else 3306, database, userName, pwd, createDatabaseStatement)

  override def testDockerBaseImage =
    DatabaseDockerImage("dockerep-0.mtl.mnubo.com/test-mysql:5.6.24", 3306, "root", "root", Some("-e MYSQL_ROOT_PASSWORD=root"))

  override def isStarted(log: String) =
    log.contains("socket: '/var/run/mysqld/mysqld.sock'  port: 3306")
}

class MysqlConnection(schemaName: String,
                      host: String,
                      port: Int,
                      database: String,
                      userName: String,
                      pwd: String,
                      createDatabaseStatement: String) extends DatabaseConnection {
  private val connection = DriverManager.getConnection(s"jdbc:mysql://$host:$port", userName, pwd)
  private val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  df.setTimeZone(TimeZone.getTimeZone("UTC"))

  if (!hasDatabase) execute(createDatabaseStatement)

  execute("USE " + database)

  override def execute(smt: String): Unit =
    connection.createStatement().execute(smt)

  override def innerConnection: AnyRef =
    connection

  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  override def dropDatabase = {
    execute("DROP DATABASE " + database)
    execute(createDatabaseStatement)
    execute("USE " + database)
  }

  override def getInstalledMigrationVersions: Set[InstalledVersion] = {
    ensureVersionTable()

    val rs = connection
      .createStatement()
      .executeQuery(s"SELECT migration_version, migration_date FROM ${schemaName}_version")

    def readVersion = rs.getString("migration_version")
    def readDate = new DateTime(rs.getDate("migration_date").getTime).withZone(DateTimeZone.UTC)

    @tailrec
    def readResultset(acc: Set[InstalledVersion] = Set.empty[InstalledVersion]): Set[InstalledVersion] =
      if (rs.next())
        readResultset(acc + InstalledVersion(readVersion, readDate))
      else
        acc

    readResultset()
  }

  override def markMigrationAsInstalled(migrationVersion: String) =
    execute(s"INSERT INTO ${schemaName}_version (migration_version, migration_date) VALUES ('$migrationVersion', '${df.format(new Date())}')")

  override def markMigrationAsUninstalled(migrationVersion: String) =
    execute(s"DELETE FROM ${schemaName}_version WHERE migration_version = '$migrationVersion'")

  override def close() =
    connection.close()

  private def ensureVersionTable() =
    if (!hasVersionTable)
      execute(s"CREATE TABLE ${schemaName}_version (migration_version VARCHAR(255) NOT NULL, migration_date DATETIME NOT NULL, PRIMARY KEY (migration_version))")

  private def hasVersionTable =
    try {
      execute(s"SELECT * FROM ${schemaName}_version LIMIT 1")
      true
    }
    catch {
      case NonFatal(_) =>
        false
    }

  private def hasDatabase =
    try {
      execute("USE " + database)
      true
    }
    catch {
      case NonFatal(_) =>
        false
    }

}
