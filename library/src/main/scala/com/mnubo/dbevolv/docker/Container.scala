package com.mnubo.dbevolv.docker

import java.io.File

import com.mnubo.app_util.Logging
import com.mnubo.dbevolv.DatabaseDockerImage

import scala.sys.process._
import scala.annotation.tailrec

class Container(imageName: String,
                isStarted: (String, Container) => Boolean,
                port: Int,
                additionalOptions: Option[String] = None,
                forcePull: Boolean = true) extends Logging {
  import Container._

  def this (descriptor: DatabaseDockerImage) = this(descriptor.name, descriptor.isStarted, descriptor.exposedPort, descriptor.additionalOptions, false)

  private val versionedImageRegex = imageName.replace(":", "\\s+").r
  private val options = additionalOptions.map(_ + " ").getOrElse("")
  private var lastImageId: String = null

  if (forcePull || versionedImageRegex.findFirstIn("docker images".!!).isEmpty) {
    log.info(s"Pulling image $imageName.")
    execShell(s"docker pull $imageName")
  }
  else
    log.info(s"The image $imageName already exists locally. Not pulling.")

  val initialVolumes = s"-v /var/run/docker.sock:/var/run/docker.sock -v ${Docker.dockerExecutableLocation}:${Docker.dockerExecutableLocation}"
  val optionalVolumes = List(
    existsAsFile(s"${Docker.userHome}/.docker/config.json") -> s" -v ${Docker.userHome}/.docker:/root/.docker",
    existsAsFile(s"${Docker.userHome}/.dockercfg") -> s" -v ${Docker.userHome}/.dockercfg:/root/.dockercfg"
  )
  val volumes = optionalVolumes.foldLeft(initialVolumes) { case (acc, (shouldMount, volume)) => if (shouldMount) acc + volume else acc }

  val containerId = execShellAndRead(s"docker run -dt $volumes -P $options$imageName")
  require(containerId.matches(Container.IdRegex), s"The container did not start correctly: '$containerId'\n")

  val exposedPort =
    if (Docker.isInContainer)
      port
    else
      hostPort(port)
  val containerHost =
    if (Docker.isInContainer)
      s"""docker inspect --format="{{.NetworkSettings.IPAddress}}" $containerId""".!!.trim
    else
      Docker.dockerHost

  waitStarted(isStarted)

  def hostPort(port: Int) =
    Seq(
      "docker",
      "inspect",
      s"""--format='{{(index (index .NetworkSettings.Ports "$port/tcp") 0).HostPort}}'""",
      containerId
    ).!!.trim.toInt

  def stop() =
    execShell(s"docker stop $containerId")

  def start() = {
    val previousLog = execShellAndRead(s"docker logs $containerId")

    def isStartedOnNewLogs(logs: String, container: Container) =
      isStarted(logs.replace(previousLog, ""), this)

    execShell(s"docker start $containerId")

    waitStarted(isStartedOnNewLogs)
  }

  def commit(repository: String, tag: String): String = {
    lastImageId =
      execShellAndRead(s"docker commit -p $containerId $repository:$tag")

    lastImageId
  }

  def exec(cmd: Seq[String]) =
    execShellAndRead(s"docker exec -i $containerId ${cmd.mkString(" ")}")

  def push(tag: String) =
    execShell(s"docker push $tag")

  def tag(tag: String) =
    execShell(s"docker tag $lastImageId $tag")

  def remove() =
    execShell(s"docker rm -v $containerId")

  def removeImage(id: String) =
    execShell(s"docker rmi $id")

  @tailrec
  private def waitStarted(isStarted: (String, Container) => Boolean, startTS: Long = System.currentTimeMillis()): Unit = {
    val logs = execShellAndRead(s"docker logs $containerId")
    if (!isStarted(logs, this) )
    {
      Thread.sleep(100)
      if (System.currentTimeMillis() - startTS > FiveMinMaxWaitTimeForStartInMS) {
        throw new Exception(s"Could not start $containerId within a reasonable time. Container logs were:\n$logs\n")
      }
      waitStarted(isStarted, startTS)
    }
  }

  private def existsAsFile(path: String) = {
    val f = new File(path)
    f.exists && f.isFile
  }
}

object Container extends Logging {
  private val IdRegex = "[a-f0-9]+"
  private val FiveMinMaxWaitTimeForStartInMS = 5 * 60 * 1000L

  private def execShell(cmd: String) = {
    log.info(cmd)
    require(cmd.! == 0, s"Cannot execute [$cmd].")
  }

  private def execShellAndRead(cmd: String) = {
    val out = new StringBuilder
    val err = new StringBuilder
    val l = ProcessLogger(o => out.append(o + "\n"), e => err.append(e) + "\n")

    if (!cmd.startsWith("docker logs"))
      log.info(cmd)

    Process(cmd) ! l

    out.toString().trim + err.toString().trim
  }


}