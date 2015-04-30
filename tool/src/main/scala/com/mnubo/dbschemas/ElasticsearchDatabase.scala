package com.mnubo.dbschemas

import java.text.SimpleDateFormat
import java.util.Date

import com.datastax.driver.core.Cluster
import com.typesafe.config.Config
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder}
import org.elasticsearch.action.admin.indices.exists.types.TypesExistsRequest
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.{ImmutableSettings, Settings}
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders
import org.joda.time.{DateTimeZone, DateTime}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

object ElasticsearchDatabase extends Database {
  val name = "elasticsearch"

  override def openConnection(schemaName: String,
                              hosts: String,
                              port: Int,
                              userName: String,
                              pwd: String,
                              indexName: String,
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new ElasticsearchConnection(schemaName, hosts, if (port > 0) port else 9300, indexName, config)

  override def testDockerBaseImage =
    DatabaseDockerImage("dockerep-0.mtl.mnubo.com/test-elasticsearch:1.4.4", 9300, "", "")

  override def isStarted(log: String) =
    log.contains("] started")
}

class ElasticsearchConnection(schemaName: String, hosts: String, port: Int, indexName: String, config: Config) extends DatabaseConnection {
  private val client = {
    val addresses = hosts.split(",").map(new InetSocketTransportAddress(_, port))

    new TransportClient(ImmutableSettings.builder().classLoader(getClass.getClassLoader).build()).addTransportAddresses(addresses: _*)
  }
  
  private val versionTypeName = s"${schemaName}_version"

  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

  if (!indexExists) createIndex()

  override def execute(smt: String): Unit =
    throw new Exception("The Elasticsearch database does not support SQL statements, just @@package.class scripts.")

  override def innerConnection: AnyRef =
    client

  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  override def dropDatabase = {
    if (!client.admin.indices().prepareDelete(indexName).get.isAcknowledged)
      throw new Exception(s"Cannot delete index $indexName")

    createIndex()
  }

  override def getInstalledMigrationVersions: Set[InstalledVersion] = {
    ensureVersionTypeExists()

    client
      .prepareSearch(indexName)
      .setTypes(versionTypeName)
      .setQuery(QueryBuilders.matchAllQuery())
      .setSize(10000)
      .execute()
      .actionGet()
      .getHits
      .getHits
      .map(doc => InstalledVersion(doc.getId, DateTime.parse(doc.getSource.get("migration_date").asInstanceOf[String]).withZone(DateTimeZone.UTC)))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String) = {
    if (!client
      .prepareIndex(indexName, versionTypeName, migrationVersion)
      .setSource("migration_date", df.format(new Date()).asInstanceOf[Any])
      .execute
      .get
      .isCreated)
      throw new Exception(s"Cannot mark migration $migrationVersion as installed")

    client
      .admin
      .indices
      .prepareFlush(indexName)
      .setForce(true)
      .setFull(true)
      .get
  }

  override def markMigrationAsUninstalled(migrationVersion: String) = {
    if (!client
      .prepareDelete(indexName, versionTypeName, migrationVersion)
      .execute
      .get
      .isFound)
      throw new Exception(s"Cannot mark migration $migrationVersion as uninstalled")

    client
      .admin
      .indices
      .prepareFlush(indexName)
      .setForce(true)
      .setFull(true)
      .get
  }

  override def close() =
    Try(client.close())

  private def ensureVersionTypeExists() =
    if (!versionTypeExists) {
      if (!client
        .admin
        .indices
        .preparePutMapping(indexName)
        .setType(versionTypeName)
        .setSource("migration_date", "type=date,store=true,format=date_time")
        .get
        .isAcknowledged)
        throw new Exception(s"Cannot add mappings for version table in $indexName index.")
    }

  private def versionTypeExists =
    client
      .admin
      .indices
      .prepareTypesExists(indexName)
      .setTypes(versionTypeName)
      .get
      .isExists

  private def createIndex() = {
    if (!client
      .admin
      .indices
      .prepareCreate(indexName)
      .setSettings(
        "number_of_shards", config.getString("shard_number"),
        "number_of_replicas", config.getString("replica_number")
      )
      .get
      .isAcknowledged)
      throw new Exception(s"Cannot create admin index $indexName.")

    ensureVersionTypeExists()
  }

  private def indexExists =
    client
      .admin
      .indices
      .prepareExists(indexName)
      .get
      .isExists

  override def isSchemaValid: Boolean =
    true // TODO
}
