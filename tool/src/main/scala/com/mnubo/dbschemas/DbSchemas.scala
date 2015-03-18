package com.mnubo.dbschemas

import java.io.File

import com.mnubo.app_util.MnuboConfiguration
import com.typesafe.config.ConfigFactory

object DbSchemas extends App {
  val dbConfig = ConfigFactory.defaultOverrides().withFallback(ConfigFactory.parseFile(new File("db.conf")))
  val schemaName = dbConfig.getString("schema_name")
  val parser = new scopt.OptionParser[DbSchemasConfig](s"docker run -it --rm -e ENV=<environment name> dockerep-0.mtl.mnubo.com/$schemaName:latest") {
    head(s"Upgrades / downgrades the $schemaName database.")
    opt[String]('n', "namespace") action { (x, c) =>
      c.copy(namespace = Some(x)) } text("If your database needs a different instance per namespace, the namespace you are targeting.")
    opt[String]('v', "version") action { (x, c) =>
      c.copy(version = Some(x)) } text("The version you want to upgrade / downgrade to. If not specified, will upagrade to latest version.")
    opt[Unit]("drop") action { (_, c) =>
      c.copy(drop = true) } text("[DANGEROUS] Whether you want to first drop the database before migrating to the given version. WARNING! You will loose all your data, don't use this option in production!")
    help("help") text("Display this schema manager usage.")
    note("Example:")
    note(s"  docker run -it --rm -e ENV=dev dockerep-0.mtl.mnubo.com/$schemaName:latest --version=0004")
  }

  parser.parse(args, DbSchemasConfig()).foreach { argConfig =>
    DatabaseMigrator.migrate(argConfig, MnuboConfiguration.loadConfig(dbConfig))
  }
}

case class DbSchemasConfig(drop: Boolean = false, namespace: Option[String] = None, version: Option[String] = None)

