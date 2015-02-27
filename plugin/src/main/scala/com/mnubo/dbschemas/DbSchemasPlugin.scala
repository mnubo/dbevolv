package com.mnubo.dbschemas

import com.typesafe.config.ConfigFactory
import sbt._
import Keys._

object DbSchemasPlugin extends AutoPlugin {
  private val config = ConfigFactory.parseFile(new File("db.conf"))
  private val schemaName = config.getString("schema_name")

  override lazy val projectSettings = Seq(
    name := schemaName,
    version := "1.0.0",
    mainClass := Some("com.mnubo.dbschemas.DbSchemas"),
    libraryDependencies += "com.mnubo" % "dbschemas" % "1.13.1"
  )
}
