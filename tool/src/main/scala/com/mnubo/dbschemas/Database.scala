package com.mnubo.dbschemas

import java.io.Closeable

import com.typesafe.config.Config
import org.joda.time.DateTime

trait Database {
  def name: String
  def openConnection(schemaName: String,
                     host: String,
                     port: Int,
                     userName: String,
                     pwd: String,
                     schema: String,
                     createDatabaseStatement: String,
                     config: Config): DatabaseConnection
  def testDockerBaseImage: DatabaseDockerImage
  def isStarted(log: String): Boolean
}

trait DatabaseConnection extends Closeable {
  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  def dropDatabase: Unit
  def execute(smt: String): Unit
  /** Get the concrete connection this DatabaseConnection is wrapping. Ex: the com.datastax.driver.core.Session. **/
  def innerConnection: AnyRef
  def getInstalledMigrationVersions: Set[InstalledVersion]
  def markMigrationAsInstalled(migrationVersion: String)
  def markMigrationAsUninstalled(migrationVersion: String)
}

case class DatabaseDockerImage(name: String, mappedPort: Int, username: String, password: String, additionalOptions: Option[String] = None)

case class InstalledVersion(version: String, installedDate: DateTime)

object Database {
  val databases =
    List(CassandraDatabase, ElasticsearchDatabase, MysqlDatabase)
      .map(db => db.name -> db)
      .toMap
}
