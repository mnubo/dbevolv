package com.mnubo
package dbschemas

import com.datastax.driver.core.Cluster
import com.typesafe.config.Config
import collection.JavaConverters._

trait NamespaceRepository {
  def fetchNamespaces: Seq[Option[String]]
}

class IntegrationNamespaceRepository(config: Config) extends NamespaceRepository {
  def fetchNamespaces: Seq[Option[String]] = Seq(None, Option("cars"), Option("cows"), Option("printers"))
}

class CassandraNamespaceRepository(config: Config) extends NamespaceRepository {
  import CassandraNamespaceRepository._

  validateConfig(config)

  private val hosts =
    config.getString(HostsKey).split(",")
  private val port =
    config.getInt(PortKey)
  private val keyspace =
    config.getString(KeyspaceKey)
  private val clusterBuilder =
    Cluster
      .builder()
      .addContactPoints(hosts: _*)
      .withPort(port)

  override def fetchNamespaces = {
    using(clusterBuilder.build) { cluster =>
      using(cluster.connect(keyspace)) { session =>
        session
          .execute("SELECT namespace FROM namespaces")
          .all
          .asScala
          .map(_.getString("namespace"))
          .sorted
          .map(Some(_))
      }
    }
  }
}

object CassandraNamespaceRepository {
  private val HostsKey = "globalconfig.hosts"
  private val PortKey = "globalconfig.port"
  private val KeyspaceKey = "globalconfig.keyspace"

  private def validateConfig(config: Config) = {
    require(config != null, "config must not be null")

    List(HostsKey, PortKey, KeyspaceKey).foreach { key =>
      require(config.hasPath(key), s"config must have a '$key' setting to be able to fetch the list of namespaces.")
    }
  }
}
