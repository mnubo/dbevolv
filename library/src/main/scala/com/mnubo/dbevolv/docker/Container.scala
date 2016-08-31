package com.mnubo.dbevolv.docker

import java.io.File

import com.mnubo.dbevolv.DatabaseDockerImage
import com.mnubo.dbevolv.util.Logging
import com.spotify.docker.client.DockerClient.{ExecCreateParam, LogsParam, RemoveContainerParam}
import com.spotify.docker.client.messages.{ContainerConfig, HostConfig}

import scala.annotation.tailrec

class Container(docker: Docker,
                imageName: String,
                isStarted: (String, Container) => Boolean,
                port: Int,
                envVars: Set[String] = Set.empty,
                forcePull: Boolean = true) extends Logging with AutoCloseable {
  import Container._

  def this (docker: Docker, descriptor: DatabaseDockerImage) = this(docker, descriptor.name, descriptor.isStarted, descriptor.exposedPort, descriptor.envVars, false)

  private var lastImageId: String = null

  if (forcePull || !docker.images.contains(imageName)) {
    log.info(s"Pulling image $imageName.")
    docker.client.pull(imageName, docker.authFor(imageName))
  }
  else
    log.info(s"The image $imageName already exists locally. Not pulling.")

  val volumes =
    if (existsAsFile(s"${docker.userHome}/.docker/config.json"))
      Seq(SocketVolume, s"${docker.userHome}/.docker:/root/.docker")
    else
      Seq(SocketVolume)

  private val creationInfo = docker.client.createContainer(
    ContainerConfig
      .builder
      .volumes(volumes: _*)
      .image(imageName)
      .hostConfig(HostConfig.builder.publishAllPorts(true).build)
      .env(envVars.toSeq: _*)
      .build
  )

  val containerId = creationInfo.id
  docker.client.startContainer(containerId)
  private val inspectionInfo = docker.client.inspectContainer(containerId)

  val exposedPort =
    if (docker.isInContainer)
      port
    else
      hostPort(port)
  val containerHost =
    if (docker.isInContainer)
      inspectionInfo.networkSettings.ipAddress
    else
      docker.dockerHost

  waitStarted(isStarted)

  def hostPort(port: Int) =
    inspectionInfo
      .networkSettings
      .ports
      .get(s"$port/tcp")
      .get(0)
      .hostPort
      .toInt

  def stop() =
    docker.client.stopContainer(containerId, 5)

  def start() = {
    val previousLog = logs

    def isStartedOnNewLogs(logs: String, container: Container) =
      isStarted(logs.replace(previousLog, ""), this)

    docker.client.startContainer(containerId)

    waitStarted(isStartedOnNewLogs)
  }

  def commit(repository: String, tag: String): String = {
    docker.client.pauseContainer(containerId)
    lastImageId = docker.client.commitContainer(containerId, repository, tag, ContainerConfig.builder.build, "", "dbevolv").id()
    docker.client.unpauseContainer(containerId)

    lastImageId
  }

  def exec(cmd: Seq[String]) = {
    val execId = docker.client.execCreate(containerId, cmd.toArray, ExecCreateParam.attachStdout, ExecCreateParam.attachStderr)
    docker.client.execStart(execId).readFully
  }

  def tag(tag: String) =
    docker.client.tag(lastImageId, tag)

  def remove() =
    docker.client.removeContainer(containerId, RemoveContainerParam.removeVolumes())

  def logs =
    docker.client.logs(containerId, LogsParam.stdout, LogsParam.stderr).readFully()

  @tailrec
  private def waitStarted(isStarted: (String, Container) => Boolean, startTS: Long = System.currentTimeMillis()): Unit = {
    val currentLogs = logs
    if (!isStarted(currentLogs, this) )
    {
      Thread.sleep(100)
      if (System.currentTimeMillis() - startTS > FiveMinMaxWaitTimeForStartInMS) {
        throw new Exception(s"Could not start $containerId within a reasonable time. Container logs were:\n$currentLogs\n")
      }
      waitStarted(isStarted, startTS)
    }
  }

  private def existsAsFile(path: String) = {
    val f = new File(path)
    f.exists && f.isFile
  }

  override def close =
    try stop()
    finally remove()
}

object Container extends Logging {
  private val FiveMinMaxWaitTimeForStartInMS = 5 * 60 * 1000L
  private val SocketVolume = "/var/run/docker.sock:/var/run/docker.sock"
}