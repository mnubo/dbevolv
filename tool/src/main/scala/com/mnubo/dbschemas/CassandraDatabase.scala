package com.mnubo
package dbschemas

import java.text.SimpleDateFormat
import java.util.Date

import com.datastax.driver.core.{ConsistencyLevel, SimpleStatement, Session, Cluster}
import com.mnubo.app_util.Logging
import com.mnubo.test_utils.cassandra.DockerCassandra
import com.typesafe.config.Config
import org.joda.time.{DateTimeZone, DateTime}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object CassandraDatabase extends Database {
  val name = "cassandra"

  override def openConnection(schemaName: String,
                              hosts: String,
                              port: Int,
                              userName: String,
                              pwd: String,
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new CassandraConnection(
      schemaName,
      hosts,
      if (port > 0) port else 9042,
      createDatabaseStatement,
      config.getInt("max_schema_agreement_wait_seconds"),
      config.getBoolean("force_pull_verification_db")
    )

  override def testDockerBaseImage =
    DatabaseDockerImage(
      name        = "dockerep-0.mtl.mnubo.com/test-cassandra:2.1.11",
      exposedPort = 9042,
      isStarted   = (log, _) => log.contains("Listening for thrift clients...")
    )
}

class CassandraConnection(
                           schemaName: String,
                           hosts: String,
                           port: Int,
                           createDatabaseStatement: String,
                           maxSchemaAgreementWaitSeconds: Int,
                           forcePullVerificationDb: Boolean) extends DatabaseConnection with Logging {
  private val cluster = Cluster
    .builder()
    .addContactPoints(hosts.split(","): _*)
    .withMaxSchemaAgreementWaitSeconds(maxSchemaAgreementWaitSeconds)
    .withPort(port)
    .build()
  private val session = cluster.connect()
  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private var keyspace: String = null

  override def setActiveSchema(keyspace: String) {
    this.keyspace = keyspace
    if (!hasKeyspace) execute(createDatabaseStatement.replace("@@DATABASE_NAME@@", keyspace))
    execute("USE " + keyspace)
  }

  override def execute(smt: String): Unit =
    session.execute(new SimpleStatement(smt).setConsistencyLevel(ConsistencyLevel.ALL))

  override def innerConnection: AnyRef =
    session

  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  override def dropDatabase() = {
    cluster
      .getMetadata
      .getKeyspace(keyspace)
      .getTables.asScala
      .map(_.getName)
      .foreach(tbl => execute("TRUNCATE " + tbl))

    execute("DROP KEYSPACE " + keyspace)

    execute(createDatabaseStatement.replace("@@DATABASE_NAME@@", keyspace))

    execute("USE " + keyspace)
  }

  override def getInstalledMigrationVersions: Set[InstalledVersion] = {
    ensureVersionTable()

    session
      .execute(s"SELECT migration_version, migration_date, checksum FROM ${schemaName}_version")
      .all()
      .asScala
      .map(row => InstalledVersion(
        row.getString("migration_version"),
        new DateTime(row.getDate("migration_date").getTime).withZone(DateTimeZone.UTC),
        row.getString("checksum")
      ))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String, checksum: String, isRebase: Boolean) = {
    if (isRebase)
      execute(s"TRUNCATE ${schemaName}_version")
    execute(s"INSERT INTO ${schemaName}_version (migration_version, migration_date, checksum) VALUES ('$migrationVersion', '${df.format(new Date())}', '$checksum')")
  }

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
      execute(s"CREATE TABLE ${schemaName}_version (migration_version TEXT, migration_date TIMESTAMP, checksum TEXT, PRIMARY KEY (migration_version))")

  private def hasVersionTable =
    try {
      execute(s"SELECT * FROM ${schemaName}_version LIMIT 1")
      true
    }
    catch {
      case NonFatal(ex) =>
        log.warn(s"Could not determine version of $schemaName'", ex)
        false
    }

  private def hasKeyspace =
    try {
      execute("USE " + keyspace)
      true
    }
    catch {
      case NonFatal(ex) =>
        log.warn(s"Could not use keyspace $keyspace'", ex)
        false
    }

  override def isSchemaValid: Boolean = {
    val installed = getInstalledMigrationVersions.map(_.version).toSeq.sorted

    if (installed.isEmpty) // Fixing MM-2961
      true // Not really true, but impossible to verify.
    else {
      val currentVersion = installed.last

      val currentSchema = schema(session, keyspace)

      val expectedSchema = using(DockerCassandra(schemaName, currentVersion, forcePullVerificationDb))(cass => schema(cass.client, schemaName))  // For test instances, there is only one keyspace named after the schemaName

      expectedSchema.isCompatibleWith(currentSchema)
    }
  }

  private def schema(session: Session, ks: String) =
    Schema(
      session
        .getCluster
        .getMetadata
        .getKeyspace(ks)
        .getTables
        .asScala
        .map { tbl =>
          Table(
            tbl.getName,
            tbl
              .getColumns
              .asScala
              .map { c =>
                Column(c.getName, c.getType.getName.toString)
              }
              .toSet
          )
        }
    )
}

