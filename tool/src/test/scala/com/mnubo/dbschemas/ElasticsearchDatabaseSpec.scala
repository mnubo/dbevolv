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
    "manage the list of installed migrations" ignore withSut { sut =>
      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("V0_4_1_2", "V0_5_0_0")

      sut
        .markMigrationAsInstalled("V0_6_0_0")

      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("V0_4_1_2", "V0_5_0_0", "V0_6_0_0")

      sut
        .markMigrationAsUninstalled("V0_6_0_0")

      sut
        .getInstalledMigrationVersions
        .map(_.version)
        .toSeq.sorted shouldEqual Seq("V0_4_1_2", "V0_5_0_0")
    }
    "detect that a schema is compatible" ignore withSut { sut =>
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
