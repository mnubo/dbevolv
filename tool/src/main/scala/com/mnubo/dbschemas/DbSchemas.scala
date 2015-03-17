package com.mnubo.dbschemas

import java.io.File

import com.mnubo.app_util.MnuboConfiguration
import com.typesafe.config.ConfigFactory

object DbSchemas extends App {
  if (args.size < 1)
    usage()
  else
    migrate(
      args(0),
      if (args.size > 1) Some(args(1)) else None
    )

  def usage() = {
    println("dbschemas: upgrades / downgrades databases.")
    println("documentation: http://git-lab1.mtl.mnubo.com/mnubo/dbschemas/tree/master")
  }

  def migrate(schema: String,
              targetVersion: Option[String]) = {
    val databases =
      List(CassandraDatabase)
        .map(db => db.name -> db)
        .toMap

    val config = MnuboConfiguration.loadConfig(ConfigFactory.parseFile(new File("db.conf")))

    val db = databases(config.getString("database_kind"))

    DatabaseMigrator.migrate(db, config, schema, targetVersion)
  }
}
