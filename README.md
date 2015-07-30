dbschemas
=========

Allows to upgrade / downgrade our database instances to a particular version. It supports multiple logical databases in the same physical database/keyspace.

Supported databases
-------------------

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
    /project/plugins.sbt
            /build.properties
    /migrations/0010/
               /0020/
               /0030/
               /0040/
               /0050/

The `db.conf` should contain the description of the database schema. You must also specify the connection strings for all the environments. For example:

    database_kind = cassandra
    hasInstanceForEachNamespace = false
    schema_name = reverse_geo
    app_name = reverse_geo-schema-manager
    create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }"

    workstation {
      host = "localhost"
    }
    dev {
      host = "atca-mnu1-s06.mtl.mnubo.com,atca-mnu1-s09.mtl.mnubo.com,atca-mnu1-s13.mtl.mnubo.com"
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

* **database_kind**: which kind of database we are targeting. See "Supported databases" for valid values.
* **hasInstanceForEachNamespace**: whether this database have a distinct instance for each of the namespaces. Default is 'false' (the database is a 'global' one).
* **schema_name**: the logical name of this database schema.
* **app_name**: the name of this schema manager (required by [app-util](http://git-lab1.mtl.mnubo.com/mnubo/app-util/tree/master)).
* **schema_version**: the migration version the given environment is supposed to be at. If not specified, all migrations will be applied. Specifying it is mandatory for dev, qa, preprod, sandbox, and prod. 
* **host**: the host or hosts name(s) to connect to.
* **port**: the port to connect to. Leave empty for default port.
* **username**: the username to use to connect to the database instance. Certain kind of databases like Cassandra don't need that.
* **password**: the password to use to connect to the database instance. Certain kind of databases like Cassandra don't need that.
* **create_database_statement**: The CQL / SQL / HQL statement to use if the database does not even exists when running the schema manager. The `@@DATABASE_NAME@@` place holder will automatically be replaced by the actual schema / keyspace name (see also "Computing the database name / schema name / index name / keyspace" below).
* **name_provider_class**: See "Computing the database name / schema name / index name / keyspace" below.
* **shard_number**: for Elasticsearch, how many shards the index should have.
* **replica_number**: for Elasticsearch, in how many additional replicas each shard should be replicated (0 means no replication).
* **max_schema_agreement_wait_seconds**: for Cassandra, sets the maximum time to wait for schema agreement before returning from a DDL query (default: 30).

Note: most of the settings can have a default value at the top, but can be overriden for a given environment. See for example `create_database_statement` in the above example.

The `build.sbt` file should activate the `dbschemas` SBT plugin that will take care of everything:

    enablePlugins(DbSchemasPlugin)

The `version.sbt` file should contain the initial version of this particular schema manager. Always 1.0.0 for new project, this will be automatically managed in Jenkins after each build:

    version in ThisBuild := "1.0.0"

The `build.properties` file should contain which SBT version to use:

    sbt.version=0.13.8

The `plugins.sbt` should point to this plugin on Artifactory (the funky piece of code make sure to always use the latest version available from Artifactory):

    resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/"
    
    val latestPluginVersion = Using.urlInputStream(new URL("http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/com/mnubo/dbschemas_2.10/maven-metadata.xml")) { stream =>
      """<latest>([\d\.]+)</latest>""".r.findFirstMatchIn(IO.readStream(stream)).get.group(1)
    }
    
    addSbtPlugin("com.mnubo" % "dbschemas-sbt-plugin" % latestPluginVersion)

The directories names in `/migrations` constitute the migration versions. Migrations will be applied in the lexical order of those directory names. Ex: when asking dbschema to upgrade to version '0002' in the above example, '0001' will be executed first, then '0002'.

Migration directories must contain 2 files named `upgrade.???` and `downgrade.???`. The extension depend on the database type. For example, for Cassandra:

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

   1) the connection to the database. Exact type of the connection depends on the database type.
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

Computing the database name / schema name / index name / keyspace (depending on underlying db kind)
---------------------------------------------------------------------------------------------------

The actual database / keyspace name will be computed the following way:

* **for global databases**: the logical schema_name will be used.
    * Ex: reverse_geo
* **for databases per namespace**: the name will be suffixed with the customer's namespace.
    * Ex: ingestion_connectedevice

Sometimes, this is not suitable. For example, QA keyspace names might be totally custom. Or historical keyspaces might be jammed together. For all these use cases, you can customize the keyspace name provider. For example:

    package com.mnubo.ingestion

    class LegacyDatabaseNameProvider extends DatabaseNameProvider {
      private val default = new DefaultDatabaseNameProvider

      def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = 
        namespace
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

    docker run -it --rm -e ENV=<environment name> dockerep-0.mtl.mnubo.com/<schema_name>-mgr:latest --help

This should result to something like:

    Upgrades / downgrades the enrichment database to the given version for all the namespaces.
    Usage: docker run -it --rm -v $HOME/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $(which docker):/bin/docker -e ENV=<environment name> dockerep-0.mtl.mnubo.com/enrichment-mgr:latest [options]

      -v <value> | --version <value>
            The version you want to upgrade / downgrade to. If not specified, will upgrade to latest version.
      --history
            Display history of database migrations instead of migrating the database.
      --help
            Display this schema manager usage.

    Note: 
      the volume mounts are only necessary when upgrading a schema. You can omit them when downgrading, getting help, or display the history.

    Example:
      docker run -it --rm -v $HOME/.dockercfg:/root/.dockercfg -v /var/run/docker.sock:/run/docker.sock -v $(which docker):/bin/docker -e ENV=dev dockerep-0.mtl.mnubo.com/enrichment:latest --version 0004
      
Note: the help message is slightly different for the databases that don't have one instance by namespace (global databases).

### Behaviour

The schema manager will upgrade one namespace at a time. For each namespace, it will apply (or downgrade) all the necessary migration to reach the target version. If one of the namespaces upgrade fail, it stopped. It is recommended to rollback all namespaces to the origin version immediately, so the faulty migration could be fixed and reapplied to all of the migrations. Since migrations are checksumed, you cannot have a system with different flavours of the same migrations. This would make any subsequent upgrades or downgrades to fail immediately.

The schema manager will also perform some validation before starting to upgrade. It will check that the schema of the target instance match the expected schema (tables, columns, types).

Inspecting the migrations inside a schema manager
-------------------------------------------------

    docker run -it --rm --entrypoint=/bin/bash dockerep-0.mtl.mnubo.com/<schema_name>-mgr:latest
    ls -la /app/migrations

Getting the list of already installed migrations in a database
--------------------------------------------------------------

    docker run -it --rm -e ENV=<environment name> dockerep-0.mtl.mnubo.com/<schema_name>-mgr:latest --history
    
Example output in dev on the enrichment Cassandra database:

    History of sparkdemoconnectedcars @ vm21-hulk-priv,vm22-hulk-priv,vm23-hulk-priv:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of sparkdemowearables @ vm21-hulk-priv,vm22-hulk-priv,vm23-hulk-priv:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of sparkdemoagriculture @ vm21-hulk-priv,vm22-hulk-priv,vm23-hulk-priv:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of connectedevice @ vm21-hulk-priv,vm22-hulk-priv,vm23-hulk-priv:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac
    History of julconnectedevice @ vm21-hulk-priv,vm22-hulk-priv,vm23-hulk-priv:
    
             Version                       Date                           Checksum
                0010   2015-05-11T00:00:00.000Z   555f57888cf9bea47e97bf6c9b7e9d3f
                0020   2015-05-11T00:00:00.000Z   c123fc10e716daf6275dfe67efa1efac

Using a test instance in automated tests
----------------------------------------

Each time a new migration is pushed to Gitlab, Jenkins will generate a test database instance with all the tables up to date. To start it:

    docker run -dt -p <database kind standard port>:<desired_port> dockerep-0.mtl.mnubo.com/test-<schema_name>:latest

For example, with the Cassandra reverse_geo database:

    docker run -dt -p 40155:9042 dockerep-0.mtl.mnubo.com/test-reverse_geo:latest

This will start a Cassandra instance, with a `reverse_geo` keyspace (the logical database name) containing all the reverse_geo tables up to date. You can point your tests to use the 40155 port on the DOCKER_HOST in order to create a session.


