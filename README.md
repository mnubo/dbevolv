<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [dbevolv](#dbevolv)
  - [Supported data stores](#supported-data-stores)
- [Usage](#usage)
  - [Writing database migrations](#writing-database-migrations)
  - [Building more complex upgrade / downgrade scripts](#building-more-complex-upgrade--downgrade-scripts)
  - [Migration design guidelines](#migration-design-guidelines)
  - [Rebasing a database](#rebasing-a-database)
  - [Getting the list of tenants](#getting-the-list-of-tenants)
  - [Computing the database name / schema name / index name / keyspace (depending on underlying db kind)](#computing-the-database-name--schema-name--index-name--keyspace-depending-on-underlying-db-kind)
  - [Testing your newly added script locally before committing](#testing-your-newly-added-script-locally-before-committing)
  - [Project examples](#project-examples)
  - [Upgrading / downgrading a database](#upgrading--downgrading-a-database)
    - [Behaviour](#behaviour)
    - [Common errors](#common-errors)
  - [Inspecting the migrations inside a schema manager](#inspecting-the-migrations-inside-a-schema-manager)
  - [Getting the list of already installed migrations in a database](#getting-the-list-of-already-installed-migrations-in-a-database)
  - [Using a test instance in automated tests](#using-a-test-instance-in-automated-tests)
- [Development](#development)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

dbevolv
=======

Allows to evolve data store instances. Supports automatic testing, multi-tenancy, test database generation, custom scripts, big data stores.

Supported data stores
---------------------

* cassandra
* elasticsearch
* mysql

Usage
=====

Writing database migrations
---------------------------

Create a git repo with the following structure:

    /db.conf
    /build.sbt
    /version.sbt
    /.gitignore
    /project/plugins.sbt
            /build.properties
    /migrations/0010/
               /0020/
               /0030/
               /0040/
               /0050/

The `db.conf` should contain the description of the data store schema. You must also specify the connection strings for all the environments. For example:

    database_kind = cassandra
    has_instance_for_each_tenant = true
    schema_name = reverse_geo
    app_name = reverse_geo-schema-manager
    create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }"

    test_configurations = [
      {
        tenant = "mycustomer1"
      },
      {
        tenant = "mycustomer2"
      }
    ]

    dev {
      host = "my-dev-cassandra-host1,my-dev-cassandra-host2,my-dev-cassandra-host3"
      schema_version = "0050"
    }
    qa {
      host = "<the qa host>"
      schema_version = "0040"
    }
    preprod {
      host = "<the preprod host>"
      schema_version = "0030"
    }
    sandbox {
      host = "<the sandbox host>"
      create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 2 }"
      schema_version = "0030"
    }
    prod {
      host = "<the prod host>"
      create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 3 }"
      schema_version = "0030"
    }

Here are the different parameters you can configure:

* **database_kind**: which kind of data store we are targeting. See "Supported data stores" for valid values.
* **has_instance_for_each_tenant**: whether this database have a distinct instance for each of your tenants. Default is 'false' (the database is a 'global' one).
* **tenant_repository_class**: when `has_instance_for_each_tenant` is true, you must supply a tenant repository. See 'Getting the list of tenants' below.
* **schema_name**: the logical name of this database schema.
* **app_name**: the name of this schema manager (required by [app-util](http://git-lab1.mtl.mnubo.com/mnubo/app-util/tree/master)).
* **schema_version**: the migration version the given environment is supposed to be at. If not specified, all migrations will be applied. Specifying it is mandatory for dev, qa, preprod, sandbox, and prod.
* **docker_namespace**: optional. The namespace under which the various docker images will be tagged. Ex: if set to myregistry, the docker images will be tagged as myregistry/xyz.
* **host**: the host or hosts name(s) to connect to.
* **port**: the port to connect to. Leave empty for default port.
* **username**: the username to use to connect to the data store instance. Certain kind of data stores like Cassandra don't need that.
* **password**: the password to use to connect to the data store instance. Certain kind of data stores like Cassandra don't need that.
* **create_database_statement**: The CQL / SQL / HQL statement to use if the database does not even exists when running the schema manager. The `@@DATABASE_NAME@@` place holder will automatically be replaced by the actual schema / keyspace name (see also "Computing the database name / schema name / index name / keyspace" below).
* **name_provider_class**: See "Computing the database name / schema name / index name / keyspace" below.
* **shard_number**: for Elasticsearch, how many shards the index should have.
* **replica_number**: for Elasticsearch, in how many additional replicas each shard should be replicated (0 means no replication).
* **max_schema_agreement_wait_seconds**: for Cassandra, sets the maximum time to wait for schema agreement before returning from a DDL query (default: 30).
* **test_configurations**: configurations for which to generate test instances during the `buildTestContainer` task. This allows you to have various keyspaces / indices / databases within the test containers.
* **tenant**: only used within `configurations`, defines a tenant name for which a test db instance should be created.

Note: most of the settings can have a default value at the top, but can be overriden for a given environment. See for example `create_database_statement` in the above example.

The `build.sbt` file should activate the `Dbevolv` SBT plugin that will take care of everything:

    enablePlugins(DbevolvPlugin)

    organization := "your organization name"

The `version.sbt` file should contain the initial version of this particular schema manager. Always 1.0.0 for new project, this will be automatically managed in Jenkins after each build:

    version in ThisBuild := "1.0.0"

The `build.properties` file should contain which SBT version to use:

    sbt.version=0.13.11

The `plugins.sbt` should point to this plugin on Artifactory (the funky piece of code make sure to always use the latest version available from Artifactory):

    addSbtPlugin("com.mnubo" % "dbevolv-sbt-plugin" % "1.0.10")

The directories names in `/migrations` constitute the migration versions. Migrations will be applied in the lexical order of those directory names. Ex: when asking dbevolv to upgrade to version '0002' in the above example, '0001' will be executed first, then '0002'.

Migration directories must contain 2 files named `upgrade.???` and `downgrade.???`. The extension depend on the data store type. For example, for Cassandra:

    /migrations/0001/upgrade.cql
                    /downgrade.cql

The upgrade file should contain what it takes to upgrade a given database to the given version. The downgrade file should contain what it takes to downgrade from the given version to the previous version.

Each statement can be laid out on multiple lines, and be terminated by a ';' character. Lines starting with a '#' character will be considered as comments and will be ignored. Empty lines are ignored.

Building more complex upgrade / downgrade scripts
-------------------------------------------------

If you need complex logic, you can create a custom Java / Scala class and reference it as if it was a statement, with the '@@' keyword at the begining of the line. Example:

    CREATE TABLE .... ;
    # Some comment
    @@com.mnubo.platform_cassandra.UpgradeFields;

Your class should be located at the usual Maven/SBT location. In this example: src/main/scala/com/mnubo/platform_cassandra/UpgradeFields.java. It must have a constructor with no parameters, and an execute method taking a 2 parameters.

   1) the connection to the database. Exact type of the connection depends on the data store type.
   2) the name of the database (postgres) / schema (mysql) / keyspace (cassandra) / index (elasticsearch)

Example:

    package com.mnubo.platform_cassandra;

    public class UpgradeFields {
        public void execute(com.datastax.driver.core.Session connection, String dataseName) {
          // Your upgrade logic here
        }
    }

Note: you can also use this trick in downgrade scripts.

If your custom script needs additional dependencies, you can add them in a `build.sbt` file through the libraryDependencies SBT key. See [SBT documentation](http://www.scala-sbt.org/0.13/docs/Library-Management.html)

Migration design guidelines
---------------------------

You migrations MUST be (and will be tested for):

* **Idempotency**. The schema manager _must_ be able to run a migration twice without any effect on the end result. This is critical when something goes wrong during the application on a migration in production. We must be able to retry a fixed version of a faulty migration, or the same migration after a corruption is fixed.
* **Perfect rollback**. When we have dozens of namespaces, the likelyhood of failure in one of them is great. In that case, the schema manager _must_ be able to rollback all the already migrated namespaces to stay consistent. Even if the upgrades involved a loss of information. In that situation, the upgrade must store the lost information somewhere to be able to retrieve it when rolling back.
* **Immutable in production**. Once a migration has been applied to production, you cannot modify the migration anymore. If you do, the schema manager will refuse to execute any further migrations.
* **Forward compatible for the application in production**. Obviously, it must not break the current version of the application. In other words, your migration must support both the new and the old version of your application.

Rebasing a database
-------------------

Sometimes, especially when a database came through a lot of migrations, you are in a situation where lots of databases and columns are created in earlier migrations to be removed in later migrations, making the database quite long to create. This is especially problematic in multi-tenant databases that gets created for a new tenant. It can also happen that there is too many migrations, and that makes the build pretty long.

A solution to that problem is to 'rebase' the migrations. It means taking the result of all of those migrations, and make a single script having the same end result as them. dbevolv helps you do that in a safe way.

Begin by creating a new migration directory, with a new version number. Put a single script `rebase.*ql` in it. Do not create a `rollback.*ql` file, rolling back a rebase migration is not supported. Ex:

    /migrations/2000/rebase.cql
    
dbevolv does not support the automatic filling of that script right now. So you will have to use the existing data store tools to forge it for you from the latest test image (see below).

As always, you can test locally that your rebase script is sound by running:

    sbt buildTestContainer

Rebase migrations are treated a bit differently than the others. Lets take an example where we do have the following migrations: 

    0010
    0020
    0030
    0100 (rebase)
    0110
    0200 (rebase)
    0210

* first, dbevolv will make sure that the database resulting from a rebase script is the same as the one resulting from all the previous migrations.
* your rebase migrations do not need to be idempotent.
* when applied on an existing database (version  <= `0030` in our example), the rebase scripts will not be applied, but all metadata about previous migrations up to the rebase will be erased. This is done for each rebase encountered along the way. For example, if the database had these migrations installed before: `[0010, 0020]`, then after running the schema manager it would have `[0200, 0210]`.
* when applied on a new database, dbevolv will start at the latest rebase, and only apply further migrations. In our example, it would apply only `0200` and `0210` because `200` is the latest migration of type `rebase`.
* **WARNING!**: once rebased, you cannot go back to previous migrations anymore. Which means that rolling back a database will only bring you to the previous rebase, even if you asked to rollback to a previous migration. For example, if a database is at version `0210` in our example, rolling back to `0030` will actually only bring the database to `0200`.

**Cleanup**: after a given `rebase` migration has been applied to all environments, you can safely delete the previous migration directories from the build.

Getting the list of tenants
---------------------------

In order for dbevolv to know for which tenants to create and maintain a database instance, you need to provide a class implementing TenantRepository. You can place the class in `src/main/java/...` or `src/main/scala/...`, or just reference the jar in the dependencies in your `build.sbt`:

    libraryDependencies += "mycompany" % "mytenantrepo" % "1.0.0"

The constructor must have one and one argument only, which is the typesafe config loaded from the `db.conf` file. This allows you to configure your class easily.

    package mycompany.tenants

    import com.mnubo.dbevolv._

    class MyTenantRepository(config: Config) extends TenantRepository {
      // Configuration specific to this particular repository
      private val host = config.getString("tenantdb.host")
      private val port = config.getInt("tenantdb.port")
      private val dbConnectionPool = ...

      override def fetchTenants = {
        using(dbConnectionPool) { connection =>
          // Pseudo JDBC like API
          connection
            .execute("SELECT customer_name FROM customer")
            .map(row => row.getString("customer_name"))
            .sorted
        }
      }
    }

Then, you could add your repository specific configuration in the `db.conf` file. In the previous fictitious example, it would look like:

    has_instance_for_each_tenant = true
    tenant_repository_class = "mycompany.tenants.MyTenantRepository"
    tenantdb.host = "mydbhost"
    tenantdb.port = 3306


Computing the database name / schema name / index name / keyspace (depending on underlying db kind)
---------------------------------------------------------------------------------------------------

The actual database / keyspace name will be computed the following way:

* **for global databases**: the logical schema_name will be used.
    * Ex: myappdb
* **for databases per tenant**: the name will be suffixed with the customer's / tenant name.
    * Ex: myappdb_mycustomer

Sometimes, this is not suitable. For example, QA keyspace names might be totally custom. Or historical keyspaces might be jammed together. For all these use cases, you can customize the keyspace name provider. For example:

    package com.mnubo.ingestion

    import com.typesafe.config.Config

    class LegacyDatabaseNameProvider extends DatabaseNameProvider {
      private val default = new DefaultDatabaseNameProvider

      def computeDatabaseName(schemaLogicalName: String, tenant: Option[String], config: Config) =
        tenant
    }

And then, in your `db.conf` file, you need to override the default database name provider in the relevant environments:

    prod {
      host = "<the prod host>"
      name_provider_class = "com.mnubo.ingestion.LegacyDatabaseNameProvider"
    }

Testing your newly added script locally before committing
---------------------------------------------------------

Just run:

    sbt buildTestContainer

This will build the test container, through all the migration scripts. You can then look at 'Using a test instance in automated tests' to connect to this instance and verify it is working well.

Project examples
----------------

* [cassandra-reverse-geocoding](http://git-lab1.mtl.mnubo.com/mnubo/cassandra-reverse-geocoder/tree/master)
* [elasticsearch-analytics-basic-index](http://git-lab1.mtl.mnubo.com/mnubo/elasticsearch-analytics-basic-index/tree/master)

Upgrading / downgrading a database
----------------------------------

To get usage:

    docker run -it --rm -e ENV=<environment name> <schema_name>-mgr:latest --help

This should result to something like:

    Upgrades / downgrades the mydatabase database to the given version for all the tenants.
    Usage: docker run -it --rm -v $HOME/.docker/:/root/.docker/ -v /var/run/docker.sock:/var/run/docker.sock -v $(which docker):$(which docker) -e ENV=<environment name> mydatabase-mgr:latest [options]

      -v <value> | --version <value>
            The version you want to upgrade / downgrade to. If not specified, will upgrade to latest version.
      -t <value> | --tenant <value>
            The tenant you want to upgrade / downgrade to. If not specified, will upgrade all tenants.
      --history
            Display history of database migrations instead of migrating the database.
      --help
            Display this schema manager usage.

    Note: 
      the volume mounts are only necessary when upgrading a schema. You can omit them when downgrading, getting help, or display the history.

    Example:
      docker run -it --rm -v $HOME/.docker/:/root/.docker/ -v /var/run/docker.sock:/var/run/docker.sock -v $(which docker):$(which docker) -e ENV=dev mydatabase:latest --version 0004
      
Note: the help message is slightly different for the databases that don't have one instance by tenant (global databases).

### Behaviour

The schema manager will upgrade one tenant at a time. For each tenant, it will apply (or downgrade) all the necessary migration to reach the target version. If one of the tenants upgrade fail, it stopped. It is recommended to rollback all tenants to the origin version immediately, so the faulty migration could be fixed and reapplied to all of the migrations. Since migrations are checksumed, you cannot have a system with different flavours of the same migrations. This would make any subsequent upgrades or downgrades to fail immediately.

The schema manager will also perform some validation before starting to upgrade. It will check that the schema of the target instance match the expected schema (tables, columns, types).

### Common errors

If the database has been corrupted so that smooth migration is impossible, you will see a message explaining the issue(s) encountered and how to fix it:

    18:10:04.988 [main] ERROR com.mnubo.dbevolv.Table - Table mytable does not contain a column somecolumn (type = text)
    18:10:04.989 [main] ERROR com.mnubo.dbevolv.Table - Table mytable does not contain a column someothercolumn (type = double)
    Exception in thread "main" java.lang.Exception: Oops, it seems the target schema of orb3a1 is not what it should be... Please call your dearest developer for helping de-corrupting the database.
            at com.mnubo.dbevolv.DatabaseMigrator$.upgrade(DatabaseMigrator.scala:91)
            at com.mnubo.dbevolv.DatabaseMigrator$.migrate(DatabaseMigrator.scala:69)
            ...

In this particular example, to repair the database, you need to create the `objectmodel` and `hdfs_import_period_sec` columns in the  `odainterpolationparams` table. You should then be able to restart the upgrade.

Inspecting the migrations inside a schema manager
-------------------------------------------------

    docker run -it --rm --entrypoint=/bin/bash <schema_name>-mgr:latest
    ls -la /app/migrations

Getting the list of already installed migrations in a database
--------------------------------------------------------------

    docker run -it --rm -e ENV=<environment name> <schema_name>-mgr:latest --history
    
Example output in dev on the mydatabase Cassandra database:

    History of myfirstcustomer @ host1,host2,host3:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of mysecondcustomer @ host1,host2,host3:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of mythirdcustomer @ host1,host2,host3:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac

Using a test instance in automated tests
----------------------------------------

Each time a new migration is pushed to Gitlab, Jenkins will generate a test database instance with all the tables up to date. To start it:

    docker run -dt -p <database kind standard port>:<desired_port> test-<schema_name>:latest

For example, with the Cassandra reverse_geo database:

    docker run -dt -p 40155:9042 test-reverse_geo:latest

This will start a Cassandra instance, with a `reverse_geo` keyspace (the logical database name) containing all the reverse_geo tables up to date. You can point your tests to use the 40155 port on the DOCKER_HOST in order to create a session.

Development
===========

The schema manager builder is actually a SBT plugin. To test the sbt plugin, we are using the scripted sbt plugin (yes, a pluging to test a plugin...). To launch the (quite long) tests, do:

    sbt library/publishLocal scripted

And go fetch a cup of coffee, you'll have time.

If you want to runs tests only on one kind of data store, specify the test build directory you want to fire (relative to src/sbt-test:

    sbt library/publishLocal "scripted schema-manager-generator/cassandradb"

Documentation for the scripted plugin is not the best. You can find a tutorial here: [Testing SBT plugins](http://eed3si9n.com/testing-sbt-plugins)
