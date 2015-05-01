package com.mnubo
package dbschemas

import com.mnubo.dbschemas.docker.Docker
import com.mnubo.test_utils.cassandra.DockerCassandra
import com.mnubo.test_utils.elasticsearch.DockerElasticsearch
import com.typesafe.config.ConfigFactory
import org.elasticsearch.client.transport.TransportClient
import org.scalatest.{Matchers, WordSpec}

class ElasticsearchDatabaseSpec extends WordSpec with Matchers {
  "An Elasticsearch database abstraction" should {
    "manage the list of installed migrations" in withSut { sut =>
      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("0001")

      sut
        .markMigrationAsInstalled("0002")

      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("0001", "0002")

      sut
        .markMigrationAsUninstalled("0002")

      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("0001")
    }
    "detect that a schema is compatible" in withSut { sut =>
      sut.isSchemaValid shouldBe true

      sut
        .innerConnection
        .asInstanceOf[TransportClient]
        .admin
        .indices
        .prepareDeleteMapping("analytics_basic_index")
        .setType("user")
        .get

      sut.isSchemaValid shouldBe false
    }
  }

  def withSut(test: DatabaseConnection => Unit) = {
    using(DockerElasticsearch("analytics_basic_index", "0001")) { es =>
      val sut = ElasticsearchDatabase.openConnection(
        "analytics_basic_index",
        Docker.dockerHost,
        es.port,
        "",
        "",
        "analytics_basic_index",
        "",
        ConfigFactory.empty())

      test(sut)
    }
  }
}
