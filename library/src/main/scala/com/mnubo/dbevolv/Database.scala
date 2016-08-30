package com.mnubo.dbevolv

import java.io.Closeable

import com.mnubo.dbevolv.docker.{Container, Docker}
import com.typesafe.config.Config
import org.joda.time.DateTime

import scala.util.Try

trait Database {
  def name: String
  def openConnection(docker: Docker,
                     schemaName: String,
                     host: String,
                     port: Int,
                     userName: String,
                     pwd: String,
                     config: Config): DatabaseConnection
  def testDockerBaseImage: DatabaseDockerImage

  def testDockerImageName(dockerNamespace: Option[String], schemaName: String, currentVersion: String) =
    dockerNamespace.map(_ + "/").getOrElse("") + "test-" + schemaName + ":" + currentVersion
}

trait DatabaseConnection extends Closeable {
  def setActiveSchema(schema: String, config: Config)
  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  def dropDatabase(config: Config): Unit
  def execute(smt: String): Unit
  /** Get the concrete connection this DatabaseConnection is wrapping. Ex: the com.datastax.driver.core.Session. **/
  def innerConnection: AnyRef
  def getInstalledMigrationVersions: Set[InstalledVersion]
  def markMigrationAsInstalled(migrationVersion: String, checksum: String, isRebase: Boolean)
  def markMigrationAsUninstalled(migrationVersion: String)
  def isSchemaValid: Boolean
  def isSameSchema(connection:DatabaseConnection): Boolean
}

case class DatabaseDockerImage(name: String,
                               exposedPort: Int,
                               isStarted: (String, Container) => Boolean,
                               username: String = "",
                               password: String = "",
                               envVars: Set[String] = Set.empty,
                               flushCmd: Option[Seq[String]] = None)

case class InstalledVersion(version: String, installedDate: DateTime, checksum: String)

object Database {
  val databases =
    List("CassandraDatabase", "ElasticsearchDatabase", "Elasticsearch2Database", "MysqlDatabase")
      .filter { dbName =>
        Try(
          getClass
            .getClassLoader
            .loadClass(s"com.mnubo.dbevolv.$dbName$$")
        ).isSuccess
      }
      .map { dbName =>
        val db =
          getClass
            .getClassLoader
            .loadClass(s"com.mnubo.dbevolv.$dbName$$")
            .getField("MODULE$")
            .get(null)
            .asInstanceOf[Database]

        db.name -> db
      }
      .toMap
}
