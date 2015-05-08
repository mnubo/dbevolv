enablePlugins(DbSchemasPlugin)

import java.net.ServerSocket
import java.nio.file.{Paths, Files}
import java.security.MessageDigest
import java.sql.{ResultSet, Connection, DriverManager}

import com.mnubo._
import com.mnubo.test_utils.docker.Docker._

import scala.annotation.tailrec
import sys.process.{Process => SProcess, ProcessLogger => SProcessLogger}

TaskKey[Unit]("check-mgr") := {
  val port = using(new ServerSocket(0))(_.getLocalPort)
  val logger = streams.value.log
  def runAndListen(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = SProcessLogger(o => out.append(o), e => err.append(e))

    logger.info(cmd)
    SProcess(cmd) ! l
    out.toString.trim + err.toString.trim
  }
  def run(cmd: String) = {
    logger.info(cmd)
    SProcess(cmd) !
  }

  val mysqlContainerId =
    runAndListen(s"docker run -d -p $port:3306 -e MYSQL_ROOT_PASSWORD=root dockerep-0.mtl.mnubo.com/test-mysql:5.6.24")

  def isStarted =
    runAndListen(s"docker logs $mysqlContainerId")
      .contains("socket: '/var/run/mysqld/mysqld.sock'  port: 3306")

  @tailrec
  def waitStarted: Unit =
    if (!isStarted) {
      Thread.sleep(100)
      waitStarted
    }

  waitStarted

  assert(run(s"docker run -i --rm --link $mysqlContainerId:mysql -e ENV=integration dockerep-0.mtl.mnubo.com/mysqldb-mgr:1.0.0-SNAPSHOT") == 0, "The schema manager failed.")

  new com.mysql.jdbc.Driver()
  using(DriverManager.getConnection(s"jdbc:mysql://$host:$port", "root", "root")) { connection: Connection =>
    using(connection.createStatement().executeQuery("SELECT COUNT(1) FROM mysqldb.kv")) { rs: ResultSet =>
      rs.next()

      assert(rs.getInt(1) == 0, "Could not query the created table")
    }
    using(connection.createStatement().executeQuery("SELECT migration_version, migration_date, checksum FROM mysqldb.mysqldb_version")) { rs: ResultSet =>
      rs.next()

      assert(rs.getString(1) == "0001", "The version has not been set properly in metadata")

      val bytes = Files.readAllBytes(Paths.get("migrations/0001/upgrade.sql"))
      val checksum = MessageDigest
        .getInstance("MD5")
        .digest(bytes)
        .map("%02x".format(_))
        .mkString

      assert(rs.getString(3) == checksum, "The checksum has not been set properly in metadata")

      assert(!rs.next(), "There was too much metadata")
    }
  }

  s"docker stop $mysqlContainerId".!
  s"docker rm $mysqlContainerId".!
  s"docker rmi -f dockerep-0.mtl.mnubo.com/mysqldb-mgr:1.0.0-SNAPSHOT".!
  s"docker rmi -f dockerep-0.mtl.mnubo.com/mysqldb-mgr:latest".!
}

