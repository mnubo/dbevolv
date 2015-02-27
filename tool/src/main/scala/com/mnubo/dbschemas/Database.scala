package com.mnubo.dbschemas

import java.io.Closeable

trait Database {
  def name: String
  def openConnection(host: String, port: Int, userName: String, pwd: String, schema: String): DatabaseConnection
}

trait DatabaseConnection extends Closeable {
  def execute(smt: String)
  def getInstalledMigrationVersions: Set[String]
  def markMigrationAsInstalled(migrationVersion: String)
  def markMigrationAsUninstalled(migrationVersion: String)
}
