package com.mnubo
package dbevolv

import java.io.File

import com.mnubo.app_util.MnuboEnvironments._
import com.mnubo.app_util.{Logging, MnuboConfiguration}
import com.typesafe.config.{Config, ConfigFactory}

object Dbevolv extends App with Logging {
  private val config =
    ConfigFactory
      .defaultOverrides()
      .withFallback(
        MnuboConfiguration.loadConfig(
          ConfigFactory
            .parseFile(new File("db.conf"))
            .withFallback(ConfigFactory.load())
        )
      )
      .withFallback(
        ConfigFactory.load()
      )
  private val env =
    System.getenv("ENV")
  private val isSensitiveEnvironment =
    Set(Dev, Qa, Sandbox, Prod, QaSandbox).contains(env)
  private val hasInstanceForEachTenant =
    config.getBoolean("hasInstanceForEachTenant")
  private val schemaName =
    config.getString("schema_name")

  private val parser =
    new scopt.OptionParser[DbevolvArgsConfig](s"docker run -it --rm -v $$HOME/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $$(which docker):/bin/docker -e ENV=<environment name> $schemaName-mgr:latest") {

      if (hasInstanceForEachTenant)
        head(s"Upgrades / downgrades the $schemaName database to the given version for all the tenants.")
      else
        head(s"Upgrades / downgrades the $schemaName database to the given version.")

      opt[String]('v', "version") action { (x, c) =>
        c.copy(version = Some(x)) } text "The version you want to upgrade / downgrade to. If not specified, will upgrade to latest version."

      if (hasInstanceForEachTenant)
        opt[String]('t', "tenant") action { (x, c) =>
          c.copy(tenantSpecified = true, tenant = if(x.isEmpty) None else Some(x)) } text "The tenant you want to upgrade / downgrade to. If not specified, will upgrade all tenants."

      if (!isSensitiveEnvironment) {
        opt[Unit]("drop") action { (_, c) =>
          c.copy(drop = true)
        } text "[DANGEROUS] Whether you want to first drop the database before migrating to the given version. WARNING! You will loose all your data, don't use this option in production!"
      }

      opt[Unit]("history") action { (_, c) =>
        c.copy(cmd = DisplayHistory) } text "Display history of database migrations instead of migrating the database."

      help("help") text "Display this schema manager usage."

      note("")
      note("Note:")
      note("  the volume mounts are only necessary when upgrading a schema. You can omit them when downgrading, getting help, or display the history.")

      note("")
      note("Example:")
      note(s"  docker run -it --rm -v $$HOME/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $$(which docker):/bin/docker -e ENV=dev $schemaName-mgr:latest --version=0004")

    }

  parser.parse(args, DbevolvArgsConfig()).foreach { argConfig =>

    if (argConfig.drop && isSensitiveEnvironment)
      throw new Exception("Sorry, the --drop option is not available in dev, qa, preprod, sandbox, or prod.")

    val version = argConfig
      .version
      .orElse {
        val cfgVersion = config.getString("schema_version")
        if (cfgVersion == "latest")
          None
        else
          Some(cfgVersion)
      }

    if (version.isEmpty && isSensitiveEnvironment)
      throw new Exception("Sorry, you have to define a 'schema_version' in your 'db.conf' for dev, qa, preprod, sandbox, and prod.")

    val tenants =
      if (argConfig.tenantSpecified)
        Seq(argConfig.tenant)
      else if (hasInstanceForEachTenant) {
        val tenantRepository =
          getClass
            .getClassLoader
            .loadClass(config.getString("tenant_repository_class"))
            .getConstructor(classOf[Config])
            .newInstance(config)
            .asInstanceOf[TenantRepository]

        tenantRepository.fetchTenants.map(Some(_))
      }
      else
        Seq(None)

    using(Database.databases(config.getString("database_kind")).openConnection(
      schemaName,
      config.getString("host"),
      config.getInt("port"),
      config.getString("username"),
      config.getString("password"),
      config.getString("create_database_statement"),
      config
    )) { connection =>
      tenants.foreach { ns =>
        val cfg = DbMigrationConfig(connection, argConfig, config, ns, version)

        argConfig.cmd match {
          case Migrate =>
            DatabaseMigrator.migrate(cfg)
          case DisplayHistory =>
            DatabaseInspector.displayHistory(cfg)
        }
      }
    }
  }
}

sealed trait DbCommand
case object Migrate extends DbCommand
case object DisplayHistory extends DbCommand
