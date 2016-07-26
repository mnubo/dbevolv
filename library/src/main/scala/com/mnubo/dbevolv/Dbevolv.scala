package com.mnubo
package dbevolv

import com.mnubo.dbevolv.docker.Docker
import com.mnubo.dbevolv.util.Logging
import com.typesafe.config.Config

object Dbevolv extends App with Logging {
  private val config = DbevolvConfiguration.loadConfig()
  private val hasInstanceForEachTenant =
    config.getBoolean("has_instance_for_each_tenant")
  private val schemaName =
    config.getString("schema_name")

  private val parser =
    new scopt.OptionParser[DbevolvArgsConfig](s"docker run -it --rm -v $$HOME/.docker/:/root/.docker/ -v /var/run/docker.sock:/var/run/docker.sock -v $$(which docker):$$(which docker) -e ENV=<environment name> $schemaName-mgr:latest") {

      if (hasInstanceForEachTenant)
        head(s"Upgrades / downgrades the $schemaName database to the given version for all the tenants.")
      else
        head(s"Upgrades / downgrades the $schemaName database to the given version.")

      opt[String]('v', "version") action { (x, c) =>
        c.copy(version = Some(x)) } text "The version you want to upgrade / downgrade to. If not specified, will upgrade to latest version."

      if (hasInstanceForEachTenant)
        opt[String]('t', "tenant") action { (x, c) =>
          c.copy(tenantSpecified = true, tenant = if(x.isEmpty) None else Some(x)) } text "The tenant you want to upgrade / downgrade to. If not specified, will upgrade all tenants."

      opt[Unit]("history") action { (_, c) =>
        c.copy(cmd = DisplayHistory) } text "Display history of database migrations instead of migrating the database."

      help("help") text "Display this schema manager usage."

      note("")
      note("Note:")
      note("  the volume mounts are only necessary when upgrading a schema. You can omit them when downgrading, getting help, or display the history.")

      note("")
      note("Example:")
      note(s"  docker run -it --rm -v $$HOME/.docker/:/root/.docker/ -v /var/run/docker.sock:/var/run/docker.sock -v $$(which docker):$$(which docker) -e ENV=dev $schemaName-mgr:latest --version=0004")

    }

  parser.parse(args, DbevolvArgsConfig()).foreach { argConfig =>

    val version = argConfig
      .version
      .orElse {
        require(config.hasPath("schema_version"), "Sorry, you have to define a 'schema_version' in your 'db.conf'.")
        val cfgVersion = config.getString("schema_version")
        if (cfgVersion == "latest")
          None
        else
          Some(cfgVersion)
      }

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

    try {
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
          val cfg = DbMigrationConfig(connection, config, ns, version.filter(_ != "latest"))

          argConfig.cmd match {
            case Migrate =>
              DatabaseMigrator.migrate(cfg)
            case DisplayHistory =>
              DatabaseInspector.displayHistory(cfg)
          }
        }
      }
    }
    finally {
      Docker.client.close()
    }
  }
}

sealed trait DbCommand
case object Migrate extends DbCommand
case object DisplayHistory extends DbCommand
