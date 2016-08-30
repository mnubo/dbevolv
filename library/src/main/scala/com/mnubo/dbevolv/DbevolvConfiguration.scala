package com.mnubo.dbevolv

import java.io.File

import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions}

object DbevolvConfiguration {
  def loadConfig(env: String = System.getenv("ENV")): Config = {
    require(env != null && !env.trim.isEmpty, "You must provide an ENV environment variable for the configuration to pick the right properties.")

    val generalConfig = ConfigFactory
      .parseFile(new File("db.conf"))
      .withFallback(ConfigFactory.load(ConfigParseOptions.defaults().setClassLoader(getClass.getClassLoader)))

    def maybeConfig(path: String) =
      if (generalConfig.hasPath(path)) Some(generalConfig.getConfig(path)) else None

    List(Some(ConfigFactory.defaultOverrides()), maybeConfig(env), maybeConfig("default"), Some(generalConfig))
      .flatten
      .reduce(_ withFallback _)
  }
}
