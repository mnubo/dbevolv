package com.mnubo.dbschemas

import java.io.Closeable

trait Database {
  def name: String
  def openConnection(schemaName: String,
                     host: String,
                     port: Int,
                     userName: String,
                     pwd: String,
                     schema: String,
                     createDatabaseStatement: String): DatabaseConnection
}

trait DatabaseConnection extends Closeable {
  def execute(smt: String): Unit
  /** Get the concrete connection this DatabaseConnection is wrapping. Ex: the com.datastax.driver.core.Session. **/
  def innerConnection: AnyRef
  def getInstalledMigrationVersions: Set[String]
  def markMigrationAsInstalled(migrationVersion: String)
  def markMigrationAsUninstalled(migrationVersion: String)
}
