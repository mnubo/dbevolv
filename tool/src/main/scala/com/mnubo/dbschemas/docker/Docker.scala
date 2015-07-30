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

  val dockerHost =
    if (hostVar != null)
      HostParseRegex.findFirstIn(hostVar).get
    else
      "localhost"

  private def getAvailablePort =
    using(new ServerSocket(0))(_.getLocalPort)

  def run(dockerImage: DatabaseDockerImage): ContainerInfo = {
    import dockerImage._

    val hostPort = getAvailablePort

    val options = additionalOptions.map(_ + " ").getOrElse("")

    val container = execAndRead(s"docker run -dt -p $hostPort:$exposedPort $options$name")

    val info = ContainerInfo(container, hostPort)

    waitStarted(info, isStarted)

    info
  }

  def stop(container: String) =
    exec(s"docker stop $container")

  def start(container: ContainerInfo, isStarted: (String, ContainerInfo) => Boolean) = {
    val previousLog = execAndRead(s"docker logs ${container.id}")

    def isStartedOnNewLogs(logs: String, container: ContainerInfo) =
      isStarted(logs.replace(previousLog, ""), container)

    exec(s"docker start ${container.id}")

    waitStarted(container, isStartedOnNewLogs)
  }

  def commit(container: String, repository: String, tag: String): String = {
    val imageId =
      execAndRead(s"docker commit -p $container $repository:$tag")

    exec(s"docker tag -f $imageId $repository:latest")

    imageId
  }

  def push(repository: String) =
    exec(s"docker push $repository")

  def remove(container: String) =
    exec(s"docker rm $container")

  def removeImage(image: String) =
    exec(s"docker rmi $image")

  def exec(cmd: String) = {
    log.info(cmd)
    if (0 != cmd.!)
      throw new Exception(s"Cannot execute [$cmd].")
  }

  def execAndRead(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = ProcessLogger(o => out.append(o + "\n"), e => err.append(e) + "\n")

    if (!cmd.startsWith("docker logs"))
      log.info(cmd)

    Process(cmd) ! l

    out.toString().trim + err.toString().trim
  }

  @tailrec
  private def waitStarted(container: ContainerInfo, isStarted: (String, ContainerInfo) => Boolean): Unit =
    if (!isStarted(execAndRead(s"docker logs ${container.id}"), container)) {
      Thread.sleep(100)
      waitStarted(container, isStarted)
    }

}

case class ContainerInfo(id: String, realPort: Int)
