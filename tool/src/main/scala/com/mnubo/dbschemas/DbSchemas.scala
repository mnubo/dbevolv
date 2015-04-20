package com.mnubo.dbschemas

import java.io.File

import com.mnubo.app_util.MnuboConfiguration
import com.typesafe.config.{Config, ConfigFactory}

object DbSchemas extends App {
  private val config = ConfigFactory
    .defaultOverrides()
    .withFallback(MnuboConfiguration.loadConfig(ConfigFactory.parseFile(new File("db.conf")))
    .withFallback(ConfigFactory.load()))

  private val schemaName = config.getString("schema_name")

  val parser = new scopt.OptionParser[DbSchemasArgsConfig](s"docker run -it --rm -e ENV=<environment name> dockerep-0.mtl.mnubo.com/$schemaName:latest") {
    head(s"Upgrades / downgrades the $schemaName database.")
    opt[String]('n', "namespace") action { (x, c) =>
      c.copy(namespace = Some(x)) } text("If your database needs a different instance per namespace, the namespace you are targeting.")
    opt[String]('v', "version") action { (x, c) =>
      c.copy(version = Some(x)) } text("The version you want to upgrade / downgrade to. If not specified, will upagrade to latest version.")
    opt[Unit]("drop") action { (_, c) =>
      c.copy(drop = true) } text("[DANGEROUS] Whether you want to first drop the database before migrating to the given version. WARNING! You will loose all your data, don't use this option in production!")
    opt[Unit]("history") action { (_, c) =>
      c.copy(cmd = DisplayHistory) } text("Display history of database migrations instead of migrating the database.")
    help("help") text("Display this schema manager usage.")
    note("Example:")
    note(s"  docker run -it --rm -e ENV=dev dockerep-0.mtl.mnubo.com/$schemaName:latest --version=0004")
  }

  parser.parse(args, DbSchemasArgsConfig()).foreach { argConfig =>
    val cfg = buildConfig(argConfig)
    argConfig.cmd match {
      case Migrate =>
        DatabaseMigrator.migrate(cfg)
      case DisplayHistory =>
        DatabaseInspector.displayHistory(cfg)
    }
  }

  def buildConfig(args: DbSchemasArgsConfig) = {
    val nameProvider = getClass.getClassLoader
      .loadClass(config.getString("name_provider_class"))
      .newInstance()
      .asInstanceOf[DatabaseNameProvider]
    val name = nameProvider.computeDatabaseName(schemaName, args.namespace)

    DbMigrationConfig(
      Database.databases(config.getString("database_kind")),
      schemaName,
      config.getString("host"),
      config.getInt("port"),
      config.getString("username"),
      config.getString("password"),
      nameProvider.computeDatabaseName(schemaName, args.namespace),
      config.getString("create_database_statement").replace("@@DATABASE_NAME@@", name),
      args.drop,
      args.version,
      config
    )
  }
}

case class DbSchemasArgsConfig(drop: Boolean = false, namespace: Option[String] = None, version: Option[String] = None, cmd: DbCommand = Migrate)

case class DbMigrationConfig(db: Database,
                             schemaName: String,
                             host: String,
                             port: Int,
                             username: String,
                             password: String,
                             name: String,
                             createDatabaseStatement: String,
                             drop: Boolean,
                             version: Option[String],
                             wholeConfig: Config)

sealed trait DbCommand

case object Migrate extends DbCommand
case object DisplayHistory extends DbCommand
