enablePlugins(DbevolvPlugin)

import java.net.InetAddress

import org.apache.commons.io.FileUtils
import java.security.MessageDigest
import com.mnubo.dbevolv.using
import com.mnubo.dbevolv.docker._
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentFactory}
import org.elasticsearch.client.{Client, Requests}
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.cluster.health.ClusterHealthStatus
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.plugin.deletebyquery.DeleteByQueryPlugin
import org.elasticsearch.search.SearchHit

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal
import sys.process.{Process => SProcess, ProcessLogger => SProcessLogger}
import collection.JavaConverters._

TaskKey[Unit]("check-mgr") := {
  val logger = streams.value.log

  def runShellAndListen(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = SProcessLogger(o => {out.append(o + "\n"); logger.info(o)}, e => {err.append(e) + "\n"; logger.error(e)})

    logger.info(cmd)
    SProcess(cmd) ! l
    out.toString.trim + err.toString.trim
  }

  def runShell(cmd: String) = {
    logger.info(cmd)
    SProcess(cmd) !
  }

  val indexName = "elasticsearchdb"
  val typeName = "kv"

  using(new Docker(None)) { docker =>

    case class Elasticsearch() extends AutoCloseable {
      val esContainer = new Container(
        docker,
        "mnubo/elasticsearch:2.3.5",
        isStarted _,
        9300
      )

      private def isStarted(logs: String, container: Container) = {
        try {
          logs.contains("] indices into cluster_state") &&
          using(TransportClient.builder()
            .settings(Settings.builder().build())
            .build()
            .addTransportAddresses(new InetSocketTransportAddress(InetAddress.getByName(container.containerHost), container.exposedPort))) { tempClient =>
            val health = tempClient
              .admin()
              .cluster()
              .prepareHealth()
              .get

            health.getStatus == ClusterHealthStatus.GREEN
          }
        }
        catch {
          case NonFatal(ex) =>
            logger.trace(ex)
            false
        }
      }

      val client = TransportClient.builder()
        .settings(Settings.builder().build())
        .addPlugin(classOf[DeleteByQueryPlugin])
        .build()
        .addTransportAddresses(new InetSocketTransportAddress(InetAddress.getByName(esContainer.containerHost), esContainer.exposedPort))

      def count =
        client
          .prepareCount(indexName)
          .setTypes(typeName)
          .setQuery(QueryBuilders.matchAllQuery())
          .get
          .getCount

      def metadata =
        client
          .prepareSearch(indexName)
          .setTypes("elasticsearchdb_version")
          .setQuery(QueryBuilders.matchAllQuery())
          .setSize(10000)
          .execute()
          .actionGet()
          .getHits
          .getHits
          .map(new Metadata(_))
          .map { m => println(m); m}
          .sortBy(_.version)
          .toSeq

      def close(): Unit = {
        client.close()
        esContainer.stop()
        esContainer.remove()
      }
    }

    case class Metadata(version: String, checksum: String) {
      def this(hit: SearchHit) = this(hit.getId, hit.getSource.get("checksum").asInstanceOf[String])
    }

    val dockerExec =
      runShellAndListen("which docker")
    val userHome =
      System.getenv("HOME")

    using(Elasticsearch()) { es =>
      import es._

      val mgrCmd =
        s"docker run -i --rm --link ${esContainer.containerId}:elasticsearch -v /var/run/docker.sock:/var/run/docker.sock -v $dockerExec:$dockerExec -v $userHome/.docker/:/root/.docker/ -e ENV=integration elasticsearchdb-mgr:1.0.0-SNAPSHOT"

      logger.info("TEST: Run the schema manager to migrate the db to latest version")
      assert(
        runShell(mgrCmd) == 0,
        "The schema manager failed."
      )

      assert(
        count == 2L,
        "Could not query the created table"
      )

      logger.info("Pwd: " + new File(".").getCanonicalPath)
      val bytesScala1 = FileUtils.readFileToByteArray(new File("src/main/scala/elasticsearchdb/ScalaUp0001.scala"))
      val bytesEs1 = FileUtils.readFileToByteArray(new File("migrations/0001/upgrade.es"))
      val checksum1 = MessageDigest
        .getInstance("MD5")
        .digest(bytesScala1 ++ bytesEs1)
        .map("%02x".format(_))
        .mkString

      val bytesJava2 = FileUtils.readFileToByteArray(new File("src/main/java/elasticsearchdb/JavaUp0002.java"))
      val bytesScala2 = FileUtils.readFileToByteArray(new File("src/main/scala/elasticsearchdb/ScalaUp0002.scala"))
      val bytesEs2 = FileUtils.readFileToByteArray(new File("migrations/0002/upgrade.es"))
      val checksum2 = MessageDigest
        .getInstance("MD5")
        .digest(bytesJava2 ++ bytesScala2 ++ bytesEs2)
        .map("%02x".format(_))
        .mkString

      val expectedMetadata = Seq(
        Metadata("0001", checksum1),
        Metadata("0002", checksum2)
      )

      assert(
        metadata == expectedMetadata,
        s"Actual metadata ($metadata) do not match expected ($expectedMetadata)"
      )

      logger.info("TEST: Run the schema manager to display history")
      val history =
        runShellAndListen(s"$mgrCmd --history")
      logger.info(history)
      val historyRegex =
        ("""History of elasticsearchdb:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum1 + """\s+0002\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum2).r
      assert(
         historyRegex.findFirstIn(history).isDefined,
        s"The schema manager did not report history properly. Expected \n$history\n\nto match $historyRegex"
      )

      logger.info("TEST: Run the schema manager to downgrade to previous version")
      runShell(s"$mgrCmd --version 0001")

      assert(
        count == 0,
        "Downgrade did not bring back the schema to the expected state"
      )

      assert(
        metadata == Seq(Metadata("0001", checksum1)),
        "Metadata is not updated correctly after a downgrade"
      )


      logger.info("TEST: Fiddle with checksum and make sure the schema manager refuses to proceed")
      client
        .prepareUpdate(indexName, "elasticsearchdb_version", "0001")
        .setDoc("checksum", "abc")
        .get()

      assert(
        runShell(mgrCmd) != 0,
        "The schema manager should not have accepted to proceed with a wrong checksum"
      )

      client
        .prepareUpdate(indexName, "elasticsearchdb_version", "0001")
        .setDoc("checksum", checksum1)
        .get()

      logger.info("TEST: Fiddle with schema and make sure the schema manager refuses to proceed")
      client
        .admin
        .indices
        .preparePutMapping(indexName)
        .setType("extra")
        .setSource(
          """
            |{
            |  "extra": {
            |    "properties": {
            |      "x": {"type": "string", "index": "not_analyzed"},
            |      "t": {"type": "string", "index": "not_analyzed"},
            |      "meta": {
            |        "type": "nested",
            |        "properties": {
            |          "category": {"type": "string", "index": "not_analyzed"}
            |        }
            |      }
            |    }
            |  }
            |}
          """.stripMargin)
        .get

      assert(
        runShellAndListen(mgrCmd).contains("The schema does not contain the table extra"),
        "The schema manager should not have accepted to proceed with a wrong schema"
      )

      client
        .admin
        .indices
        .delete(new DeleteIndexRequest(indexName))
        .get

      client
        .admin
        .indices
        .prepareCreate(indexName)
        .setSettings(
          "number_of_shards", 1.asInstanceOf[AnyRef],
          "number_of_replicas", 0.asInstanceOf[AnyRef]
        )
        .get

      client
        .admin
        .indices
        .preparePutMapping(indexName)
        .setType("elasticsearchdb_version")
        .setSource(
          "migration_date", "type=date,store=true,format=date_time",
          "checksum",        "type=string,index=not_analyzed"
        )
        .get

      client
        .prepareIndex(indexName, "elasticsearchdb_version", "0001")
        .setSource(
          "migration_date", "1970-01-01T00:00:00.000Z",
          "checksum", checksum1)
        .get
      client
        .prepareIndex(indexName, "elasticsearchdb_version", "0002")
        .setSource(
          "migration_date", "1970-01-01T00:00:00.000Z",
          "checksum", checksum2)
        .get

      client
        .admin
        .indices
        .preparePutMapping(indexName)
        .setType("kv")
        .setSource(
          """
            |{
            |  "kv": {
            |    "properties": {
            |      "k": {"type": "string", "index": "not_analyzed"},
            |      "v": {"type": "string", "index": "not_analyzed"},
            |      "meta": {
            |        "type": "nested",
            |        "properties": {
            |          "category": {"type": "string", "index": "not_analyzed"}
            |        }
            |      }
            |    }
            |  }
            |}
          """.stripMargin)
        .get

      logger.info("TEST: Finally, make sure we can re-apply latest migration")
      assert(
        runShell(mgrCmd) == 0,
        "The schema manager should have run successfully"
      )

    }

    s"docker rmi -f elasticsearchdb-mgr:1.0.0-SNAPSHOT".!
    s"docker rmi -f elasticsearchdb-mgr:latest".!
    s"docker rmi -f test-elasticsearchdb:0002".!
    s"docker rmi -f test-elasticsearchdb:0001".!
    s"docker rmi -f test-elasticsearchdb:latest".!
  }
}
