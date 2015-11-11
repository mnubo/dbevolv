package com.mnubo
package dbschemas.docker

import java.net.ServerSocket

import com.mnubo.app_util.Logging
import com.mnubo.dbschemas.DatabaseDockerImage

import scala.annotation.tailrec
import scala.sys.process._

object Docker extends Logging {
  private val HostParseRegex = """\d+\.[0-9\.]+""".r
  private val hostVar = System.getenv("DOCKER_HOST")
  private val FiveMinMaxWaitTimeForStartInMS = 5 * 60 * 1000L

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  val userHome = System.getProperty("user.home")
  val dockerExecutableLocation = "which docker".!!.trim

  private def getAvailablePort =
    using(new ServerSocket(0))(_.getLocalPort)

  def run(dockerImage: DatabaseDockerImage): ContainerInfo = {
    import dockerImage._

    val hostPort = getAvailablePort

    val options = additionalOptions.map(_ + " ").getOrElse("")

    val container = execShellAndRead(s"docker run -dt -v $userHome/.docker/config.json:/root/.docker/config.json:ro -v $userHome/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $dockerExecutableLocation:/bin/docker -p $hostPort:$exposedPort $options$name")

    val info = ContainerInfo(container, hostPort)

    waitStarted(info, isStarted)

    info
  }

  def stop(container: String) =
    execShell(s"docker stop $container")

  def start(container: ContainerInfo, isStarted: (String, ContainerInfo) => Boolean) = {
    val previousLog = execShellAndRead(s"docker logs ${container.id}")

    def isStartedOnNewLogs(logs: String, container: ContainerInfo) =
      isStarted(logs.replace(previousLog, ""), container)

    execShell(s"docker start ${container.id}")

    waitStarted(container, isStartedOnNewLogs)
  }

  def commit(container: String, repository: String, tag: String): String = {
    val imageId =
      execShellAndRead(s"docker commit -p $container $repository:$tag")

    imageId
  }

  def exec(container: String, cmd: Seq[String]) =
    execShellAndRead(s"docker exec -i $container ${cmd.mkString(" ")}")

  def push(repository: String) =
    execShell(s"docker push $repository")

  def remove(container: String) =
    execShell(s"docker rm $container")

  def removeImage(image: String) =
    execShell(s"docker rmi $image")

  def execShell(cmd: String) = {
    log.info(cmd)
    if (0 != cmd.!)
      throw new Exception(s"Cannot execute [$cmd].")
  }

  def execShellAndRead(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = ProcessLogger(o => out.append(o + "\n"), e => err.append(e) + "\n")

    if (!cmd.startsWith("docker logs"))
      log.info(cmd)

    Process(cmd) ! l

    out.toString().trim + err.toString().trim
  }

  @tailrec
  private def waitStarted(container: ContainerInfo, isStarted: (String, ContainerInfo) => Boolean, startTS:Long = System.currentTimeMillis()): Unit = {
    val logs = execShellAndRead(s"docker logs ${container.id}")
    if (!isStarted(logs, container) )
    {
      Thread.sleep(100)
      if (System.currentTimeMillis() - startTS > FiveMinMaxWaitTimeForStartInMS) {
        throw new Exception(s"Could not start $container within a reasonable time. Container logs were:\n$logs\n")
      }
      waitStarted(container, isStarted, startTS)
    }
  }
}

case class ContainerInfo(id: String, realPort: Int)
