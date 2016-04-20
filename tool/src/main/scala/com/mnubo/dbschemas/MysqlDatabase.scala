package com.mnubo
package dbschemas

import java.sql.{ResultSet, Connection, DriverManager}
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

import com.mnubo.docker_utils.mysql.DockerMySQL
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
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new MysqlConnection(
      schemaName,
      host,
      if (port > 0) port else 3306,
      userName,
      pwd,
      createDatabaseStatement,
      config.getBoolean("force_pull_verification_db"))

  override def testDockerBaseImage =
    DatabaseDockerImage(
      name              = "dockerep-0.mtl.mnubo.com/test-mysql:5.6.29",
      exposedPort       = 3306,
      isStarted         = (log, _) => log.contains("socket: '/var/run/mysqld/mysqld.sock'  port: 3306"),
      username          = "root",
      password          = "root",
      additionalOptions = Some("-e MYSQL_ROOT_PASSWORD=root")
    )
}

class MysqlConnection(schemaName: String,
                      host: String,
                      port: Int,
                      userName: String,
                      pwd: String,
                      createDatabaseStatement: String,
                      forcePullVerificationDb: Boolean) extends DatabaseConnection {
  private val connection = DriverManager.getConnection(s"jdbc:mysql://$host:$port", userName, pwd)
  private val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  df.setTimeZone(TimeZone.getTimeZone("UTC"))
  private var database: String = null

  override def setActiveSchema(database: String) {
    this.database = database
    if (!hasDatabase) execute(createDatabaseStatement.replace("@@DATABASE_NAME@@", database))
    execute("USE " + database)
  }

  override def execute(smt: String): Unit =
    connection.createStatement().execute(smt)

  override def innerConnection: AnyRef =
    connection

  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  override def dropDatabase() = {
    execute("DROP DATABASE " + database)
    execute(createDatabaseStatement)
    execute("USE " + database)
  }

  override def getInstalledMigrationVersions: Set[InstalledVersion] = {
    ensureVersionTable()

    val rs = connection
      .createStatement()
      .executeQuery(s"SELECT migration_version, migration_date, checksum FROM ${schemaName}_version")

    def readVersion = rs.getString("migration_version")
    def readChecksum = rs.getString("checksum")
    def readDate = new DateTime(rs.getDate("migration_date").getTime).withZone(DateTimeZone.UTC)

    @tailrec
    def readResultset(acc: Set[InstalledVersion] = Set.empty[InstalledVersion]): Set[InstalledVersion] =
      if (rs.next())
        readResultset(acc + InstalledVersion(readVersion, readDate, readChecksum))
      else
        acc

    readResultset()
  }

  override def markMigrationAsInstalled(migrationVersion: String, checksum: String, isRebase: Boolean) = {
    if (isRebase)
      execute(s"TRUNCATE ${schemaName}_version")

    execute(s"INSERT INTO ${schemaName}_version (migration_version, migration_date, checksum) VALUES ('$migrationVersion', '${df.format(new Date())}', '$checksum')")
  }

  override def markMigrationAsUninstalled(migrationVersion: String) =
    execute(s"DELETE FROM ${schemaName}_version WHERE migration_version = '$migrationVersion'")

  override def close() =
    connection.close()

  private def ensureVersionTable() =
    if (!hasVersionTable)
      execute(s"CREATE TABLE ${schemaName}_version (migration_version VARCHAR(255) NOT NULL, migration_date DATETIME NOT NULL, checksum VARCHAR(255), PRIMARY KEY (migration_version))")

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

  override def isSchemaValid: Boolean = {
    val installed = getInstalledMigrationVersions.map(_.version).toSeq.sorted

    if (installed.isEmpty)
      true
    else {
      val currentVersion = installed.last

      val currentSchema = schema(connection, database)

      val expectedSchema = using(DockerMySQL(schemaName, currentVersion, forcePullVerificationDb))(mysql => schema(mysql.client, schemaName)) // For test instances, there is only one database named after the schemaName

      expectedSchema.isCompatibleWith(currentSchema)
    }
  }

  override def isSameSchema(other:DatabaseConnection) : Boolean = {
    other match {
      case otherConn:MysqlConnection =>
        val mySchema = schema()
        val otherSchema = otherConn.schema()
        mySchema.isSameAs(otherSchema)
      case _ => false
    }
  }

  private def schema(): Schema[Int] = schema(connection, database)

  private def schema(connection: Connection, db: String): Schema[Int] = {
    val meta = connection.getMetaData

    Schema(
      using(meta.getTables(db, null, "%", Array("TABLE"))) { rs =>
        readResultset(rs)(_.getString("TABLE_NAME"))
          .map { tableName =>
            Table[Int](
              tableName,
              using(meta.getColumns(database, null, tableName, "%")) { rs =>
                readResultset(rs)(rs => Column(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE")))
              }
            )
          }
      }
    )
  }

  @tailrec
  private def readResultset[T](rs: ResultSet, acc: Set[T] = Set.empty[T])(extractFunction: ResultSet => T): Set[T] =
    if (rs.next())
      readResultset(rs, acc + extractFunction(rs))(extractFunction)
    else
      acc

}
