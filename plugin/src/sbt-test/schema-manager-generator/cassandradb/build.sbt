enablePlugins(DbevolvPlugin)

import java.net.ServerSocket

import org.apache.commons.io.FileUtils
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, ResultSet}
import java.util.UUID

import com.datastax.driver.core.{Cluster, Row}
import com.mnubo.dbevolv.using
import com.mnubo.dbevolv.docker.Docker
import com.mnubo.dbevolv.docker.Container

import scala.annotation.tailrec
import sys.process.{Process => SProcess, ProcessLogger => SProcessLogger}
import collection.JavaConverters._
import sbtdocker.staging.DefaultDockerfileProcessor
import sbtdocker.{DockerBuild, DockerKeys}

resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/" // Temporary while removing all of our deps

TaskKey[Unit]("check-mgr") := {
  val logger = streams.value.log
  val dockerPath = (DockerKeys.dockerPath in docker).value
  val buildOptions = (DockerKeys.buildOptions in docker).value
  val stageDir = (target in docker).value
  val dockerfile = (DockerKeys.dockerfile in docker).value
  val imageNames = (DockerKeys.imageNames in docker).value
  val pwd = new File(".").getCanonicalPath

  def rebuild(): Unit =
    DockerBuild(dockerfile, DefaultDockerfileProcessor, imageNames, buildOptions, stageDir, dockerPath, logger)

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
    case class Cassandra() extends AutoCloseable {
      val cassandraContainer = new Container(
        docker,
        "mnubo/cassandra:3.0",
        (logs, _) => logs.contains("Listening for thrift clients...") || logs.contains("Starting listening for CQL clients"),
        9042
      )

      private val cluster = Cluster
        .builder()
        .addContactPoints(cassandraContainer.containerHost)
        .withPort(cassandraContainer.exposedPort)
        .build()
      val session = cluster.connect()

      def query[T](sql: String)(readFunction: Row => T): Seq[T] = {
        logger.info(s"Executing $sql")
        session
          .execute(sql)
          .all
          .asScala
          .map(readFunction)
      }

      def execute(sql: String) = {
        logger.info(s"Executing $sql")
        session
          .execute(sql)
      }

      def close(): Unit = {
        session.close()
        cassandraContainer.stop()
        cassandraContainer.remove()
      }
    }

    case class Metadata(version: String, checksum: String) {
      def this(row: Row) = this(row.getString("migration_version"), row.getString("checksum"))
    }

    val dockerExec =
      runShellAndListen("which docker")
    val userHome =
      System.getenv("HOME")

    using(Cassandra()) { cass =>
      import cass._

      // TODO: use Container
      val mgrCmd =
        s"docker run -i --rm --link ${cassandraContainer.containerId}:cassandrahost -v $userHome/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/var/run/docker.sock -v $dockerExec:$dockerExec -v $userHome/.docker/config.json:/root/.docker/config.json:ro -e ENV=integration cassandradb-mgr:1.0.0-SNAPSHOT"

      // Run the schema manager to migrate the db to latest version
      assert(
        runShell(mgrCmd) == 0,
        "The schema manager failed."
      )

      assert(
        query("SELECT COUNT(1) as ct FROM cassandradb.kv")(_.getLong("ct")) == Seq(3L),
        "Could not query the created table"
      )

      val metadata = query("SELECT migration_version, checksum FROM cassandradb.cassandradb_version")(new Metadata(_)).sortBy(_.version)

      logger.info("Pwd: " + new File(".").getCanonicalPath)
      val checksum1 = MessageDigest
        .getInstance("MD5")
        .digest(FileUtils.readFileToByteArray(new File("migrations/0001/upgrade.cql")))
        .map("%02x".format(_))
        .mkString

      val bytesJava2 = FileUtils.readFileToByteArray(new File("src/main/java/cassandradb/JavaUp0002.java"))
      val bytesScala2 = FileUtils.readFileToByteArray(new File("src/main/scala/cassandradb/ScalaUp0002.scala"))
      val bytesSql2 = FileUtils.readFileToByteArray(new File("migrations/0002/upgrade.cql"))
      val checksum2 = MessageDigest
        .getInstance("MD5")
        .digest(bytesJava2 ++ bytesScala2 ++ bytesSql2)
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
        ("""History of cassandradb:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum1 + """\s+0002\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum2).r
      assert(
        historyRegex.findFirstIn(history).isDefined,
        "The schema manager did not report history properly."
      )

      logger.info("TEST: Run the schema manager to downgrade to previous version")
      runShell(s"$mgrCmd --version 0001")

      assert(
        query("SELECT COUNT(1) as ct FROM cassandradb.kv")(_.getLong("ct")) == Seq(0L),
        "Downgrade did not bring back the schema to the expected state"
      )

      assert(
        query("SELECT migration_version, checksum FROM cassandradb.cassandradb_version")(new Metadata(_)) == Seq(Metadata("0001", checksum1)),
        "Metadata is not updated correctly after a downgrade"
      )

      logger.info("TEST: Fiddle with checksum and make sure the schema manager refuses to proceed")
      execute("UPDATE cassandradb.cassandradb_version SET checksum='abc' WHERE migration_version = '0001'")
      assert(
        runShell(mgrCmd) != 0,
        "The schema manager should not have accepted to proceed with a wrong checksum"
      )
      execute(s"UPDATE cassandradb.cassandradb_version SET checksum='$checksum1' WHERE migration_version = '0001'")

      logger.info("TEST: Fiddle with schema and make sure the schema manager refuses to proceed")
      execute("ALTER TABLE cassandradb.kv RENAME k TO k2")
      assert(
        runShellAndListen(mgrCmd).contains("Table kv does not contain a column k (type = text)"),
        "The schema manager should not have accepted to proceed with a wrong schema"
      )
      Thread.sleep(10000)
      execute("ALTER TABLE cassandradb.kv RENAME k2 TO k")

      logger.info("TEST: Finally, make sure we can re-apply latest migration")
      // Actual migrations on our test db start here.
      assert(
        runShell(mgrCmd) == 0,
        "The schema manager should have run successfully"
      )

      try {
        logger.info("TEST: Add a bunch of new migrations (including a rebase) into the mix")
        assert(
          runShell(s"cp -R $pwd/stage2_migrations/0003 migrations/") == 0,
          "Could not copy additional migrations 0003"
        )
        assert(
          runShell(s"cp -R $pwd/stage2_migrations/0004 migrations/") == 0,
          "Could not copy additional migrations 0004"
        )
        assert(
          runShell(s"cp -R $pwd/stage2_migrations/0005 migrations/") == 0,
          "Could not copy additional migrations 0005"
        )
        rebuild()

        logger.info("TEST: Make sure we can pass through a rebase")
        assert(
          runShell(mgrCmd) == 0,
          "The schema manager should have applied the rebase and version 0005"
        )
        val meta = query("SELECT migration_version FROM cassandradb.cassandradb_version")(_.getString("migration_version")).toSet
        assert(
          meta == Set("0004", "0005"),
          s"Metadata is not updated correctly after an upgrade through a rebase: $meta"
        )

        logger.info("TEST: Make sure nothing happen when already at the last version")
        assert(
          runShell(mgrCmd) == 0,
          "The schema manager should have done nothing successfully"
        )

        logger.info("TEST: Make sure we can rollback up to a rebase")
        assert(
          runShell(s"$mgrCmd --version 0001") == 0,
          "The schema manager should have rollbacked to 0004"
        )
        assert(
          query("SELECT migration_version FROM cassandradb.cassandradb_version")(_.getString("migration_version")) == Seq("0004"),
          "Metadata is not updated correctly after a rollback up to a rebase"
        )
      }
      finally {
        assert(
          runShell(s"rm -rf $pwd/migrations/0003 $pwd/migrations/0004 $pwd/migrations/0005") == 0,
          "Cannot delete additional test migrations"
        )
      }

    }

    s"docker rmi -f cassandradb-mgr:1.0.0-SNAPSHOT".!
    s"docker rmi -f cassandradb-mgr:latest".!
    s"docker rmi -f test-cassandradb:0005".!
    s"docker rmi -f test-cassandradb:0004".!
    s"docker rmi -f test-cassandradb:0003".!
    s"docker rmi -f test-cassandradb:0002".!
    s"docker rmi -f test-cassandradb:0001".!
    s"docker rmi -f test-cassandradb:latest".!
  }
}
