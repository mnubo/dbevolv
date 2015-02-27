package com.mnubo.dbschemas

import java.io.File

import com.typesafe.config.ConfigFactory

object DbSchemas extends App {
  if (args.size < 5)
    usage()
  else
    migrate(
      args(0),
      args(1).toInt,
      args(2),
      args(3),
      args(4),
      if (args.size > 5) Some(args(5)) else None
    )

  def usage() = {
    println("dbschemas: upgrades / downgrades databases.")
    println("documentation: http://git-lab1.mtl.mnubo.com/mnubo/dbschemas/tree/master")
  }

  def migrate(host: String,
              port: Int,
              userName: String,
              pwd: String,
              schema: String,
              targetVersion: Option[String]) = {
    val databases =
      List(CassandraDatabase)
        .map(db => db.name -> db)
        .toMap

    val config = ConfigFactory.parseFile(new File("db.conf"))

    val db = databases(config.getString("database_kind"))

    DatabaseMigrator.migrate(db, config.getString("schema_name"), host, port, userName, pwd, schema, targetVersion)
  }
}
