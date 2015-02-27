package com.mnubo.dbschemas

import sbt._
import Keys._

object DbSchemasPlugin extends AutoPlugin {
  override lazy val projectSettings = Seq(
    version := "1.0.0",
    mainClass := Some("com.mnubo.dbschemas.DbSchemas"),
    libraryDependencies += "com.mnubo" % "dbschemas" % "1.0.0"
  )
}
