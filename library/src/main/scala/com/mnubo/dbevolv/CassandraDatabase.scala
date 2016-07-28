package com.mnubo
package dbevolv

import java.text.SimpleDateFormat
import java.util.Date

import com.datastax.driver.core.exceptions.InvalidQueryException
import com.datastax.driver.core.{Cluster, ConsistencyLevel, Session, SimpleStatement}
import com.mnubo.dbevolv.util.Logging
import com.mnubo.dbevolv.docker.Container
import com.typesafe.config.Config
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConverters._
import scala.util.Try
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
      config
    )

  override def testDockerBaseImage =
    DatabaseDockerImage(
      name        = "mnubo/cassandra:2.1",
      exposedPort = 9042,
      isStarted   = (log, _) => log.contains("Listening for thrift clients..."),
      flushCmd = Some(Seq("nodetool", "flush"))
    )
}

class CassandraConnection(computedDbName: String,
                          hosts: String,
                          port: Int,
                          createDatabaseStatement: String,
                          config: Config) extends DatabaseConnection with Logging {
  private val maxSchemaAgreementWaitSeconds =
    config.getInt("max_schema_agreement_wait_seconds")
  private val forcePullVerificationDb =
    config.getBoolean("force_pull_verification_db")
  private val dockerNamespace =
    if (config.hasPath("docker_namespace")) Some(config.getString("docker_namespace")) else None

  log.info(s"Opening connection on $hosts, port $port")

  private val cluster = Cluster
    .builder()
    .addContactPoints(hosts.split(","): _*)
    .withMaxSchemaAgreementWaitSeconds(maxSchemaAgreementWaitSeconds)
    .withPort(port)
    .build()
  private val session = cluster.connect()
  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
  private var keyspace: String = null
  private val schemaName: String = config.getString("schema_name")

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
        new DateTime(row.getTimestamp("migration_date").getTime).withZone(DateTimeZone.UTC),
        row.getString("checksum")
      ))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String, checksum: String, isRebase: Boolean) = {
    if (isRebase)
      execute(s"TRUNCATE ${schemaName}_version")

    log.info(s"Marking migration $migrationVersion as installed....")
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
      case NonFatal(ex:InvalidQueryException) =>
        false
    }

  private def hasKeyspace =
    try {
      execute("USE " + keyspace)
      true
    }
    catch {
      case NonFatal(ex:InvalidQueryException) =>
        false
    }

  override def isSchemaValid: Boolean = {
    val installed = getInstalledMigrationVersions.map(_.version).toSeq.sorted

    if (installed.isEmpty) // Fixing MM-2961
      true // Not really true, but impossible to verify.
    else {
      val currentVersion = installed.last

      val referenceDatabase = new Container(
        CassandraDatabase.testDockerImageName(dockerNamespace, computedDbName, currentVersion),
        CassandraDatabase.testDockerBaseImage.isStarted,
        CassandraDatabase.testDockerBaseImage.exposedPort,
        forcePull = forcePullVerificationDb,
        envVars = CassandraDatabase.testDockerBaseImage.envVars
      )

      log.info(s"Launching reference db in ${referenceDatabase.containerId}")

      try {
        using(new CassandraConnection(computedDbName, referenceDatabase.containerHost, referenceDatabase.exposedPort, createDatabaseStatement, config)) { referenceDatabaseConnection =>
          referenceDatabaseConnection.setActiveSchema(schemaName)
          isSameSchema(referenceDatabaseConnection)
        }
      }
      finally {
        Try(referenceDatabase.stop())
        Try(referenceDatabase.remove())
      }

    }
  }

  override def isSameSchema(other:DatabaseConnection) : Boolean = {
    other match {
      case otherConn: CassandraConnection =>
        val mySchema = schema()
        val otherSchema = otherConn.schema()
        log.debug(s"Comparing $this with $other :")
        log.debug(s"- $mySchema")
        log.debug(s"- $otherSchema")
        otherSchema.isSameAs(mySchema)
      case _ => false
    }
  }

  private def schema() : Schema[String]  = schema(session, keyspace)

  private def schema(session: Session, ks: String) : Schema[String] =
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

  override def toString =
    s"CassandraConnection($hosts, $port)"
}

