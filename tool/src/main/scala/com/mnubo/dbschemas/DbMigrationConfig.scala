package com.mnubo.dbschemas

import com.typesafe.config.Config

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
                             skipSchemaVerification: Boolean,
                             applyUpgradesTwice: Boolean,
                             wholeConfig: Config)

case class DbSchemasArgsConfig(drop: Boolean = false,
                               version: Option[String] = None,
                               cmd: DbCommand = Migrate)

object DbMigrationConfig {
  def apply(args: DbSchemasArgsConfig, config: Config, namespace: Option[String]): DbMigrationConfig = {
    val schemaName =
      config.getString("schema_name")

    val nameProvider =
      getClass
        .getClassLoader
        .loadClass(config.getString("name_provider_class"))
        .newInstance()
        .asInstanceOf[DatabaseNameProvider]

    val name =
      nameProvider.computeDatabaseName(schemaName, namespace)

    DbMigrationConfig(
      Database.databases(config.getString("database_kind")),
      schemaName,
      config.getString("host"),
      config.getInt("port"),
      config.getString("username"),
      config.getString("password"),
      nameProvider.computeDatabaseName(schemaName, namespace),
      config.getString("create_database_statement").replace("@@DATABASE_NAME@@", name),
      args.drop,
      args.version,
      skipSchemaVerification = false,
      applyUpgradesTwice = false,
      config
    )
  }}