package com.mnubo.dbevolv

import com.typesafe.config.Config

case class DbMigrationConfig(connection: DatabaseConnection,
                             name: String,
                             drop: Boolean,
                             version: Option[String],
                             skipSchemaVerification: Boolean,
                             applyUpgradesTwice: Boolean,
                             wholeConfig: Config) {
  connection.setActiveSchema(name)
}

case class DbevolvArgsConfig(drop: Boolean = false,
                             version: Option[String] = None,
                             cmd: DbCommand = Migrate,
                             tenantSpecified: Boolean = false,
                             tenant: Option[String] = None)

object DbMigrationConfig {
  def apply(connection: DatabaseConnection, args: DbevolvArgsConfig, config: Config, tenant: Option[String], version: Option[String]): DbMigrationConfig = {
    val schemaName =
      config.getString("schema_name")

    val nameProvider =
      getClass
        .getClassLoader
        .loadClass(config.getString("name_provider_class"))
        .newInstance()
        .asInstanceOf[DatabaseNameProvider]

    val name = nameProvider.computeDatabaseName(schemaName, tenant)

    DbMigrationConfig(
      connection,
      name,
      args.drop,
      version,
      skipSchemaVerification = false,
      applyUpgradesTwice = false,
      config
    )
  }}