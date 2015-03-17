package com.mnubo.dbschemas

import java.text.SimpleDateFormat
import java.util.Date

import com.datastax.driver.core.Cluster

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object CassandraDatabase extends Database {
  val name = "cassandra"

  override def openConnection(schemaName: String, hosts: String, port: Int, userName: String, pwd: String, keyspace: String, createDatabaseStatement: String): DatabaseConnection =
    new CassandraConnection(schemaName, hosts, keyspace, createDatabaseStatement)
}

class CassandraConnection(schemaName: String, hosts: String, keyspace: String, createDatabaseStatement: String) extends DatabaseConnection {
  private val cluster = Cluster.builder().addContactPoints(hosts.split(","): _*).build()
  private val session = cluster.connect()
  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")

  if (!hasKeyspace) execute(createDatabaseStatement)

  execute("USE " + keyspace)

  override def execute(smt: String): Unit =
    session.execute(smt)

  override def innerConnection: AnyRef =
    session

  override def getInstalledMigrationVersions: Set[String] = {
    ensureVersionTable()

    session
      .execute(s"SELECT migration_version FROM ${schemaName}_version")
      .all()
      .asScala
      .map(_.getString("migration_version"))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String) =
    execute(s"INSERT INTO ${schemaName}_version (migration_version, migration_date) VALUES ('$migrationVersion', '${df.format(new Date())}')")

  override def markMigrationAsUninstalled(migrationVersion: String) =
    execute(s"DELETE FROM ${schemaName}_version WHERE migration_version = '$migrationVersion'")

  override def close() =
    try {
      session.close()
    }
    finally {
      cluster.close()
    }

  private def ensureVersionTable() =
    if (!hasVersionTable)
      execute(s"CREATE TABLE ${schemaName}_version (migration_version TEXT, migration_date TIMESTAMP, PRIMARY KEY (migration_version))")

  private def hasVersionTable =
    try {
      execute(s"SELECT * FROM ${schemaName}_version LIMIT 1")
      true
    }
    catch {
      case NonFatal(_) =>
        false
    }

  private def hasKeyspace =
    try {
      execute("USE " + keyspace)
      true
    }
    catch {
      case NonFatal(_) =>
        false
    }
}
