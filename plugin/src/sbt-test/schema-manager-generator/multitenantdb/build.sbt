enablePlugins(DbevolvPlugin)

import java.net.ServerSocket
import org.apache.commons.io.FileUtils
import java.security.MessageDigest
import com.mnubo.dbevolv.using
import com.mnubo.dbevolv.docker._
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.elasticsearch.index.query.QueryBuilders
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

  using(new Docker(None)) { docker =>
    case class IndexSettings(indexName: String, shardNumber: Int)

    def isStarted(logs: String, container: Container) = {
      try {
        logs.contains("] indices into cluster_state") &&
          using(new TransportClient(ImmutableSettings.builder().classLoader(getClass.getClassLoader).build()).addTransportAddresses(new InetSocketTransportAddress(container.containerHost, container.exposedPort))) { tempClient =>
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

    def indexSettings(client: TransportClient) = {
      val indicesResp = client.admin.indices.prepareGetIndex.get

      indicesResp.getIndices.toSet[String].map { index =>
        val settings = indicesResp.getSettings.get(index)
//        logger.info(s"Settings for $index: ${settings.getAsMap.asScala.map { case (k,v) => s"$k: $v" }.mkString(", ")}")
        IndexSettings(index, settings.get("index.number_of_shards").toInt)
      }
    }

    def createClient(esContainer: Container) =
      new TransportClient(ImmutableSettings.builder().classLoader(getClass.getClassLoader).build()).addTransportAddresses(new InetSocketTransportAddress(esContainer.containerHost, esContainer.exposedPort))

    case class Elasticsearch() extends AutoCloseable {
      val esContainer = new Container(
        docker,
        "mnubo/elasticsearch:1.5.2",
        isStarted _,
        9300
      )

      val client = createClient(esContainer)

      def close() =
        try client.close()
        finally esContainer.close()
    }

    val userHome =
      System.getenv("HOME")

    logger.info("TEST: Check the test db contains instances for test tenants")

    using(new Container(
      docker,
      "test-multitenantdb:0001",
      isStarted _,
      9300,
      forcePull = false
    )) { testDb =>
      using(createClient(testDb)) { client =>
        val indices = indexSettings(client)
        assert(
          indices == Set(IndexSettings("multitenantdb", 1), IndexSettings("multitenantdb_mycustomer1", 1), IndexSettings("multitenantdb_awesomecustomer", 2)),
          s"Dbvolve did not create the expected test indices: $indices"
        )
      }
    }

    using(Elasticsearch()) { es =>
      import es._

      // TODO: use Container
      val mgrCmd =
        s"docker run -i --rm --link ${esContainer.containerId}:elasticsearch -v /var/run/docker.sock:/var/run/docker.sock -v $userHome/.docker/:/root/.docker/ -e ENV=integration multitenantdb-mgr:1.0.0-SNAPSHOT"

      logger.info("TEST: Run the schema manager to migrate the db to latest version")
      assert(
        runShell(mgrCmd) == 0,
        "The schema manager failed."
      )

      val indices = indexSettings(client)
      assert(
        indices == Set(IndexSettings("multitenantdb_greatcustomer", 1), IndexSettings("multitenantdb_awesomecustomer", 2)),
        s"The schema manager did not created the expected indices: $indices"
      )

      logger.info("TEST: Run the schema manager to display history")
      val history =
        runShellAndListen(s"$mgrCmd --history")
      logger.info(history)
      val historyRegex =
        """History of multitenantdb_greatcustomer:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+[a-f0-9]+\s+History of multitenantdb_awesomecustomer:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+[a-f0-9]+""".r
      assert(
        historyRegex.findFirstIn(history).isDefined,
        "The schema manager did not report history properly."
      )

    }

    s"docker rmi -f multitenantdb-mgr:1.0.0-SNAPSHOT".!
    s"docker rmi -f multitenantdb-mgr:latest".!
    s"docker rmi -f test-multitenantdb:0001".!
    s"docker rmi -f test-multitenantdb:latest".!
  }
}
