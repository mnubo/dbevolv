package com.mnubo.dbschemas

import java.text.SimpleDateFormat
import java.util.Date

import com.datastax.driver.core.Cluster

import scala.util.control.NonFatal
import collection.JavaConverters._

object CassandraDatabase extends Database {
  val name = "cassandra"

  override def openConnection(hosts: String, port: Int, userName: String, pwd: String, keyspace: String): DatabaseConnection =
    new CassandraConnection(hosts, port, userName, pwd, keyspace)
}

class CassandraConnection(hosts: String, port: Int, userName: String, pwd: String, keyspace: String) extends DatabaseConnection {
  private val cluster = Cluster.builder().addContactPoints(hosts.split(","): _*).build()
  private val session = cluster.connect()
  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")

  execute("USE " + keyspace)

  override def execute(smt: String) =
    session.execute(smt)

  override def getInstalledMigrationVersions: Set[String] = {
    ensureVersionTable()

    session
      .execute("SELECT migration_version FROM version")
      .all()
      .asScala
      .map(_.getString("migration_version"))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String) =
    execute(s"INSERT INTO version (migration_version, migration_date) VALUES ('$migrationVersion', '${df.format(new Date())}')")

  override def markMigrationAsUninstalled(migrationVersion: String) =
    execute(s"DELETE FROM version WHERE migration_version = '$migrationVersion'")

  override def close() =
    try {
      session.close()
    }
    finally {
      cluster.close()
    }

  private def ensureVersionTable() =
    if (!hasVersionTable)
      execute("CREATE TABLE version (migration_version TEXT, migration_date TIMESTAMP, PRIMARY KEY (migration_version))")

  private def hasVersionTable =
    try {
      execute("SELECT * FROM version LIMIT 1")
      true
    }
    catch {
      case NonFatal(_) =>
        false
    }
}
