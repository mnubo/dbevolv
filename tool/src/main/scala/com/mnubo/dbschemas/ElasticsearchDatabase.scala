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
import org.elasticsearch.index.query.{QueryBuilder, QueryBuilders}
import org.joda.time.{DateTime, DateTimeZone}
import spray.json._

import scala.annotation.tailrec
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
                              createDatabaseStatement: String,
                              config: Config): DatabaseConnection =
    new ElasticsearchConnection(
      schemaName,
      hosts,
      if (port > 0) port else 9300,
      config)

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

class ElasticsearchConnection(schemaName: String, hosts: String, port: Int, config: Config) extends DatabaseConnection with Logging {
  private val client = ElasticsearchDatabase.newClient(hosts, port)
  private val forcePullVerificationDb = config.getBoolean("force_pull_verification_db")
  
  private val versionTypeName = s"${schemaName}_version"

  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

  private var indexName: String = null

  override def setActiveSchema(indexName: String) {
    this.indexName = indexName
    if (!indexExists) createIndex()
  }

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

  override def markMigrationAsInstalled(migrationVersion: String, checksum: String, isRebase: Boolean) = {
    if (isRebase)
      client
        .prepareDeleteByQuery(indexName)
        .setTypes(versionTypeName)
        .setQuery(QueryBuilders.matchAllQuery())
        .get

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

      val currentSchema = schema(client, indexName)

      val expectedSchema = using(DockerElasticsearch(schemaName, currentVersion, forcePullVerificationDb)) { es =>
        schema(es.client, schemaName) // For test ES instances, there is only one index named after the schemaName
      }

      expectedSchema.isCompatibleWith(currentSchema)
    }
  }

  // Ex of mapping for an index with one type "event":
  // {
  //     "event": {
  //         "properties": {
  //             "_all": {"enabled": "false" },
  //             "x_event_type": {"type": "string", "index": "not_analyzed"},
  //             "x_pipeline": {"type": "string", "index": "not_analyzed"},
  //             "x_timestamp": {"type": "date", "format": "date_time"},
  //             "x_object": {
  //                 "type": "nested",
  //                 "properties": {
  //                     "object_id": {"type": "string", "index": "not_analyzed"},
  //                     "x_registration_latlon": {"type": "geo_point"}
  //                 }
  //             }
  //         }
  //     }
  // }

  private def schema(client: TransportClient, index: String): Schema[Map[String, String]] = {
    val response = client
      .admin
      .indices
      .prepareGetMappings(index)
      .get

    Schema(
      response
        .mappings.asScala
        .head // Only one index
        .value
        .asScala
        .map { cursor =>
          val (typeName, typeMappings) = (cursor.key, cursor.value)
          Table(
            typeName,
            parseMappingProperties(
              typeMappings
                .source().string()
                .parseJson
                .asJsObject
                .fields(typeName)
                .asJsObject
            )
          )
        }
    )
  }

  // This will return all metadata associated with each property. Nested properties will be parsed as well, and their name will be their dotted path (ex: x_object.x_owner.username).
  // Non string metadata is ignored for simplicity.
  // Ex of JSON mapping that should be passed:
  // {
  //     "properties": {
  //         "_all": {"enabled": "false" },
  //         "x_event_type": {"type": "string", "index": "not_analyzed"},
  //         "x_pipeline": {"type": "string", "index": "not_analyzed"},
  //         "x_timestamp": {"type": "date", "format": "date_time"},
  //         "x_object": {
  //             "type": "nested",
  //             "properties": {
  //                 "object_id": {"type": "string", "index": "not_analyzed"},
  //                 "x_registration_latlon": {"type": "geo_point"}
  //             }
  //         }
  //     }
  // }
  private def parseMappingProperties(mapping: JsObject, prefix: String = ""): Set[Column[Map[String, String]]] =
    mapping
      .fields("properties")
      .asJsObject
      .fields
      .toSet[(String, JsValue)]
      .flatMap { case (name, v) =>
        val property = v.asJsObject
        val typ = property
          .fields("type")
          .asInstanceOf[JsString]
          .value

        log.info(s"Found a $name property of type $typ.")
        if (typ == "nested")
          parseMappingProperties(property, prefix + name + ".")
        else {
          log.info(s"Creating a $prefix$name property of type $typ.")
          Set(
            Column(
              prefix + name,
              property
                .fields
                .filter(_._2.isInstanceOf[JsString])
                .mapValues(_.asInstanceOf[JsString].value)
            )
          )
        }
      }
}
