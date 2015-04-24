package com.mnubo.dbschemas

import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

import com.typesafe.config.Config
import org.apache.hive.jdbc.HiveDriver
import org.joda.time.{DateTime, DateTimeZone}

import scala.annotation.tailrec
import scala.util.control.NonFatal

object HiveDatabase extends Database {
  val name = "hive"

  override def openConnection(schemaName: String,
                              host: String,
                              port: Int,
                              userName: String,
                              pwd: String,
                              database: String,
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new HiveConnection(schemaName, host, if (port > 0) port else 10000, database, userName, pwd, createDatabaseStatement)

  override def testDockerBaseImage =
    DatabaseDockerImage("dockerep-0.mtl.mnubo.com/test-hive:0.12.0-cdh5.1.3", 10000, "root", "")

  override def isStarted(log: String) =
    log.contains("thrift.ThriftCLIService: ThriftBinaryCLIService listening on 0.0.0.0/0.0.0.0:10000")
}

class HiveConnection(schemaName: String,
                      host: String,
                      port: Int,
                      database: String,
                      userName: String,
                      pwd: String,
                      createDatabaseStatement: String) extends DatabaseConnection {
  java.sql.DriverManager.registerDriver(new HiveDriver)
  private val connection = DriverManager.getConnection(s"jdbc:hive2://$host:$port", userName, pwd)
  private val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.sss")
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

  // Note to the curious: Hive do not have proper support for delete, so create a partition for each version, so we can drop them in case of rollback.
  // Also, for more information about the convoluted insert syntax, see following SO question: http://stackoverflow.com/questions/17425492/hive-insert-query-like-sql
  override def markMigrationAsInstalled(migrationVersion: String) =
    execute(s"INSERT INTO TABLE ${schemaName}_version PARTITION (migration_version = '$migrationVersion') SELECT STACK(1, '${df.format(new Date())}') FROM default.one LIMIT 1")

  override def markMigrationAsUninstalled(migrationVersion: String) =
    execute(s"ALTER TABLE ${schemaName}_version DROP PARTITION (migration_version = '$migrationVersion')")

  override def close() =
    connection.close()

  private def ensureVersionTable() =
    if (!hasVersionTable)
      execute(s"CREATE TABLE ${schemaName}_version (migration_date TIMESTAMP) PARTITIONED BY (migration_version STRING)")

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
