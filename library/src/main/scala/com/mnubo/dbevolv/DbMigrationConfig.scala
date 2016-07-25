package com.mnubo.dbevolv

import com.typesafe.config.Config

case class DbMigrationConfig(connection: DatabaseConnection,
                             name: String,
                             version: Option[String],
                             skipSchemaVerification: Boolean,
                             applyUpgradesTwice: Boolean,
                             wholeConfig: Config) {
  connection.setActiveSchema(name)
}

case class DbevolvArgsConfig(version: Option[String] = None,
                             cmd: DbCommand = Migrate,
                             tenantSpecified: Boolean = false,
                             tenant: Option[String] = None)

object DbMigrationConfig {
  def apply(connection: DatabaseConnection,
            config: Config,
            tenant: Option[String],
            version: Option[String],
            skipSchemaVerification: Boolean = false,
            applyUpgradesTwice: Boolean = false): DbMigrationConfig = {
    val schemaName =
      config.getString("schema_name")

    val nameProvider =
      getClass
        .getClassLoader
        .loadClass(config.getString("name_provider_class"))
        .newInstance()
        .asInstanceOf[DatabaseNameProvider]

    val name = nameProvider.computeDatabaseName(schemaName, tenant, config)

    DbMigrationConfig(
      connection,
      name,
      version,
      skipSchemaVerification,
      applyUpgradesTwice,
      config
    )
  }}