package com.mnubo
package dbschemas

import java.text.SimpleDateFormat
import java.util.{Date, Map => JMap}

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.docker.{Docker, ContainerInfo}
import com.mnubo.test_utils.elasticsearch.DockerElasticsearch
import com.typesafe.config.Config
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders
import org.joda.time.{DateTime, DateTimeZone}

import scala.collection.JavaConverters._
import scala.util.Try

object ElasticsearchDatabase extends Database {
  val name = "elasticsearch"
  private val isStartedRegex = """recovered \[\d+\] indices into cluster_state""".r

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
    DatabaseDockerImage(
      name              = "dockerep-0.mtl.mnubo.com/test-elasticsearch:1.5.2",
      exposedPort       = 9300,
      isStarted         = isStarted,
      additionalOptions = Some("-p 9200")
    )

  private [dbschemas] def newClient(hosts: String, port: Int) = {
    val settings =
      ImmutableSettings
        .builder()
        .put("client.transport.ignore_cluster_name", true)
        .classLoader(getClass.getClassLoader)
        .build()

    val addresses =
      hosts
        .split(",")
        .map(new InetSocketTransportAddress(_, port))

    new TransportClient(settings).addTransportAddresses(addresses: _*)
  }

  private def isStarted(log: String, info: ContainerInfo) =
    isStartedRegex.findFirstIn(log).isDefined &&
      Try(using(newClient(Docker.dockerHost, info.realPort)) { tempClient =>
        tempClient
          .admin()
          .cluster()
          .prepareHealth()
          .get
          .getStatus == ClusterHealthStatus.GREEN
      }).toOption.getOrElse(false)
}

class ElasticsearchConnection(schemaName: String, hosts: String, port: Int, indexName: String, config: Config) extends DatabaseConnection with Logging {
  private val client = ElasticsearchDatabase.newClient(hosts, port)
  
  private val versionTypeName = s"${schemaName}_version"

  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

  if (!indexExists) createIndex()

  override def execute(smt: String): Unit =
    throw new Exception("The Elasticsearch database does not support SQL statements, just @@package.class scripts.")

  override def innerConnection: AnyRef =
    client

  /** For tests, or QA, we might want to recreate a database instance from scratch. Implementors should know how to properly clean an existing database. */
  override def dropDatabase() = {
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
      .map(doc => InstalledVersion(
        doc.getId,
        DateTime.parse(doc.getSource.get("migration_date").asInstanceOf[String]).withZone(DateTimeZone.UTC),
        doc.getSource.get("checksum").asInstanceOf[String]
      ))
      .toSet
  }

  override def markMigrationAsInstalled(migrationVersion: String, checksum: String) = {
    if (!client
      .prepareIndex(indexName, versionTypeName, migrationVersion)
      .setSource(
        "migration_date", df.format(new Date()).asInstanceOf[Any],
        "checksum", checksum)
      .get
      .isCreated)
      throw new Exception(s"Cannot mark migration $migrationVersion as installed")

    client
      .admin
      .indices
      .prepareFlush(indexName)
      .setForce(true)
      .setWaitIfOngoing(true)
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
      .setWaitIfOngoing(true)
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
        .setSource(
          "migration_date", "type=date,store=true,format=date_time",
          "checksum",        "type=string,index=not_analyzed"
        )
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

  override def isSchemaValid: Boolean = {
    val installed = getInstalledMigrationVersions.map(_.version).toSeq.sorted

    if (installed.isEmpty)
      true
    else {
      val currentVersion = installed.last

      val currentSchema = schema(client)

      val expectedSchema = using(DockerElasticsearch(schemaName, currentVersion)) { es =>
        schema(es.client)
      }

      expectedSchema.isCompatibleWith(currentSchema)
    }
  }

  private def schema(client: TransportClient): Schema[Map[String, String]] = {
    val response = client
      .admin
      .indices
      .prepareGetMappings(indexName)
      .get

    Schema(
      response
        .mappings.asScala
        .head // Only one index
        .value
        .asScala
        .map { cursor =>
          val (typeName, typeMappings) = (cursor.key, cursor.value)
          Table[Map[String, String]](
            typeName,
            typeMappings
              .getSourceAsMap
              .get("properties")
              .asInstanceOf[JMap[String, JMap[String, String]]]
              .asScala
              .mapValues(_.asScala)
              .map { case (fieldName, fieldMapping) =>
                Column(fieldName, Map(fieldMapping.toSeq: _*))
              }
              .toSet
          )
        }
    )
  }
}
