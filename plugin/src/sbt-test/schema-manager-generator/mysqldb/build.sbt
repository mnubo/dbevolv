enablePlugins(DbevolvPlugin)

import java.net.ServerSocket
import java.nio.file.{Paths, Files}
import java.security.MessageDigest
import java.sql.{ResultSet, Connection, DriverManager}

import com.mnubo._
import com.mnubo.docker_utils.docker.Docker._

import scala.annotation.tailrec
import sys.process.{Process => SProcess, ProcessLogger => SProcessLogger}

resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/" // Temporary while removing all of our deps

TaskKey[Unit]("check-mgr") := {
  val logger = streams.value.log

  def runShellAndListen(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = SProcessLogger(o => out.append(o + "\n"), e => err.append(e) + "\n")

    logger.info(cmd)
    SProcess(cmd) ! l
    out.toString.trim + err.toString.trim
  }

  def runShell(cmd: String) = {
    logger.info(cmd)
    SProcess(cmd) !
  }

  case class Mysql() {
    val port = using(new ServerSocket(0))(_.getLocalPort)

    runShell("docker pull mysql:5.6")

    val mysqlContainerId =
      runShellAndListen(s"docker run -d -p $port:3306 -e MYSQL_ROOT_PASSWORD=root mysql:5.6")

    private def isStarted =
      runShellAndListen(s"docker logs $mysqlContainerId")
        .contains("socket: '/var/run/mysqld/mysqld.sock'  port: 3306")

    @tailrec
    private def waitStarted: Unit =
      if (!isStarted) {
        Thread.sleep(500)
        waitStarted
      }

    waitStarted

    new com.mysql.jdbc.Driver() // Register driver
    val connection = DriverManager.getConnection(s"jdbc:mysql://$host:$port", "root", "root")

    def query[T](sql: String)(readFunction: ResultSet =>T): Seq[T] = {
      using(connection.createStatement().executeQuery(sql)) { rs: ResultSet =>
        @tailrec
        def read(acc: Seq[T] = Seq.empty[T]): Seq[T] =
          if (rs.next())
            read(acc :+ readFunction(rs))
          else
            acc

        read()
      }

    }

    def execute(sql: String) =
      connection
        .createStatement()
        .execute(sql)

    def close(): Unit = {
      connection.close()
      s"docker stop $mysqlContainerId".!
      s"docker rm -v $mysqlContainerId".!
    }
  }

  case class Metadata(version: String, checksum: String) {
    def this(rs: ResultSet) = this(rs.getString("migration_version"), rs.getString("checksum"))
  }

  val dockerExec =
    runShellAndListen("which docker")
  val userHome =
    System.getenv("HOME")

  using(Mysql()) { ms =>
    import ms._

    val mgrCmd =
      s"docker run -i --rm --link $mysqlContainerId:mysql -v $userHome/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $dockerExec:/bin/docker -v $userHome/.docker/config.json:/root/.docker/config.json:ro -e ENV=integration mysqldb-mgr:1.0.0-SNAPSHOT"

    // Run the schema manager to migrate the db to latest version
    assert(
      runShell(mgrCmd) == 0,
      "The schema manager failed."
    )

    assert(
      query("SELECT COUNT(1) as ct FROM mysqldb.kv")(_.getLong("ct")) == Seq(3L),
      "Could not query the created table"
    )

    val metadata = query("SELECT migration_version, checksum FROM mysqldb.mysqldb_version ORDER BY migration_version")(new Metadata(_))

    val checksum1 = MessageDigest
      .getInstance("MD5")
      .digest(Files.readAllBytes(Paths.get("migrations/0001/upgrade.sql")))
      .map("%02x".format(_))
      .mkString

    val bytesJava2 = Files.readAllBytes(Paths.get("src/main/java/mysqldb/JavaUp0002.java"))
    val bytesScala2 = Files.readAllBytes(Paths.get("src/main/scala/mysqldb/ScalaUp0002.scala"))
    val bytesSql2 = Files.readAllBytes(Paths.get("migrations/0002/upgrade.sql"))
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

    // Run the schema manager to display history
    val history =
      runShellAndListen(s"$mgrCmd --history")
    logger.info(history)
    val historyRegex =
      ("""History of mysqldb:\s+Version\s+Date\s+Checksum\s+0001\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum1 + """\s+0002\s+\d\d\d\d-\d\d-\d\dT\d\d:\d\d:\d\d.\d\d\dZ\s+""" + checksum2).r
    assert(
       historyRegex.findFirstIn(history).isDefined,
      "The schema manager did not report history properly."
    )

    // Run the schema manager to downgrade to previous version
    runShell(s"$mgrCmd --version 0001")

    assert(
      query("SELECT COUNT(1) as ct FROM mysqldb.kv")(_.getInt("ct")) == Seq(0),
      "Downgrade did not bring back the schema to the expected state"
    )

    assert(
      query("SELECT migration_version, checksum FROM mysqldb.mysqldb_version")(new Metadata(_)) == Seq(Metadata("0001", checksum1)),
      "Metadata is not updated correctly after a downgrade"
    )

    // Fiddle with checksum and make sure the schema manager refuses to proceed
    execute("UPDATE mysqldb.mysqldb_version SET checksum='abc' WHERE migration_version = '0001'")
    assert(
      runShell(mgrCmd) != 0,
      "The schema manager should not have accepted to proceed with a wrong checksum"
    )
    execute(s"UPDATE mysqldb.mysqldb_version SET checksum='$checksum1' WHERE migration_version = '0001'")

    // Fiddle with schema and make sure the schema manager refuses to proceed
    execute("ALTER TABLE mysqldb.kv CHANGE COLUMN v v2 VARCHAR(255)")
    assert(
      runShell(mgrCmd) != 0,
      "The schema manager should not have accepted to proceed with a wrong schema"
    )
    execute("ALTER TABLE mysqldb.kv CHANGE COLUMN v2 v VARCHAR(255)")

    // Finally, make sure we can re-apply latest migration
    assert(
      runShell(mgrCmd) == 0,
      "The schema manager should have run successfully"
    )
  }

  s"docker rmi -f mysqldb-mgr:1.0.0-SNAPSHOT".!
  s"docker rmi -f mysqldb-mgr:latest".!
  s"docker rmi -f test-mysqldb:0002".!
  s"docker rmi -f test-mysqldb:0001".!
  s"docker rmi -f test-mysqldb:latest".!
}
