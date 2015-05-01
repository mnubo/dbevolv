package com.mnubo
package dbschemas

import com.mnubo.dbschemas.docker.Docker
import com.mnubo.test_utils.cassandra.DockerCassandra
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpec}

class CassandraDatabaseSpec extends WordSpec with Matchers {
  "A Cassandra database abstraction" should {
    "manage the list of installed migrations" in withSut { sut =>
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
    "detect that a schema is compatible" in withSut { sut =>
      sut.isSchemaValid shouldBe true

      sut.execute("ALTER TABLE enrichment_version ADD dummy string")

      sut.isSchemaValid shouldBe false
    }
  }

  def withSut(test: DatabaseConnection => Unit) = {
    using(DockerCassandra("enrichment", "V0_5_0_0")) { cass =>
      val sut = CassandraDatabase.openConnection(
        "enrichment",
        Docker.dockerHost,
        cass.port,
        "",
        "",
        "enrichment",
        "",
        ConfigFactory.empty())

      test(sut)
    }
  }
}
