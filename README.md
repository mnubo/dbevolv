dbschemas
=========

Allows to upgrade / downgrade our database instances to a particular version. It supports multiple logical databases in the same physical database/keyspace.

Supported databases
-------------------

* cassandra

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
    /migrations/0001/
               /0002/
               /0003/

The `db.conf` should contain the description of the database schema. You must also specify the connection strings for all the environments. For example:

    database_kind = cassandra
    schema_name = reverse_geo
    create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }"

    workstation {
      host = "localhost"
    }
    dev {
      host = "atca-mnu1-s06.mtl.mnubo.com,atca-mnu1-s09.mtl.mnubo.com,atca-mnu1-s13.mtl.mnubo.com"
    }
    qa {
      host = "<the qa host>"
    }
    preprod {
      host = "<the preprod host>"
    }
    prod {
      host = "<the prod host>"
      create_database_statement = "CREATE KEYSPACE @@DATABASE_NAME@@ WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 3 }"
    }

Here are the different parameters you can configure:

* **database_kind**: which kind of database we are targeting. See "Supported databases" for valid values.
* **schema_name**: the logical name of this database schema.
* **host**: the host or hosts name(s) to connect to.
* **port**: the port to connect to. Leave empty for default port.
* **username**: the username to use to connect to the database instance. Certain kind of databases like Cassandra don't need that.
* **password**: the password to use to connect to the database instance. Certain kind of databases like Cassandra don't need that.
* **create_database_statement**: The CQL / SQL / HQL statement to use if the database does not even exists when running the schema manager. The `@@DATABASE_NAME@@` place holder will automatically be replaced by the actual schema / keyspace name (see also "Computing the database name / schema name / index name / keyspace" below).
* **name_provider_class**: See "Computing the database name / schema name / index name / keyspace" below.

Note: most of the settings can have a default value at the top, but can be overriden for a given environment. See for example `create_database_statement` in the above example.

The `build.sbt` file should activate the `dbschemas` SBT plugin that will take care of everything:

    enablePlugins(DbSchemasPlugin)

The `version.sbt` file should contain the initial version of this particular schema manager. Always 1.0.0 for new project, this will be automatically managed in Jenkins after each build:

    version in ThisBuild := "1.0.0"

The `build.properties` file should contain which SBT version to use:

    sbt.version=0.13.7

The `plugins.sbt` should point to this plugin on Artifactory:

    resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/"

    addSbtPlugin("com.mnubo" % "dbschemas-sbt-plugin" % "[1.13.22,)")

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
    @@com.mnubo.platform_cassandra.UpgradeFields

Your class should be located at the usual Maven/SBT location. In this example: src/main/scala/com/mnubo/platform_cassandra/UpgradeFields.java. It must have a constructor with no parameters, and an execute method taking a single parameter being the connection to the database. Exact type of the connection depends on the database type. Example:

    package com.mnubo.platform_cassandra;

    public class UpgradeFields {
        public void execute(com.datastax.driver.core.Session connection) {
          // Your upgrade logic here
        }
    }

Note: you can also use this trick in downgrade scripts.

If your custom script needs additional dependencies, you can add them in a `build.sbt` file through the libraryDependencies SBT key. See [SBT documentation](http://www.scala-sbt.org/0.13/docs/Library-Management.html)

Computing the database name / schema name / index name / keyspace (depending on underlying db kind)
---------------------------------------------------------------------------------------------------

The actual database / keyspace name will be computed the following way:

* **for global databases**: the logical schema_name will be used.
    * Ex: reverse_geo
* **for databases per customer**: the name will be suffixed with the customer's namespace.
    * Ex: ingestion_connectedevice

Sometimes, this is not suitable. For example, QA keyspace names might be totally custom. Or historical keyspaces might be jammed together. For all these use cases, you can customize the keyspace name provider. For example:

    package com.mnubo.ingestion

    class LegacyDatabaseNameProvider extends DatabaseNameProvider {
      private val default = new DefaultDatabaseNameProvider

      def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = namespace match {
        case Some(ns) if ns == "connectedevice" => ns
        case Some(ns) if ns == "vanhawks" => ns
        case _ => default.computeDatabaseName(schemaLogicalName, namespace)
      }
    }

And then, in your `db.conf` file, you need to override the default database name provider in the relevant environments:

    prod {
      host = "<the prod host>"
      name_provider_class = "com.mnubo.ingestion.LegacyDatabaseNameProvider"
    }

Example
-------

[cassandra-reverse-geocoding](http://git-lab1.mtl.mnubo.com/mnubo/cassandra-reverse-geocoder/tree/master)

Upgrading / downgrading a database
----------------------------------

The target schema/database/keyspace must already exist. dbschemas do not support the creation of the database itself.

To get usage:

    docker run -it --rm -e ENV=<environment name> docker.mnubo.com/<schema_name>:latest --help

This should result to something like:

    Upgrades / downgrades the reverse_geo database.
    Usage: docker run -it --rm -e ENV=<environment name> dockerep-0.mtl.mnubo.com/reverse_geo:latest [options]

      -n <value> | --namespace <value>
            If your database needs a different instance per namespace, the namespace your are targeting.
      -v <value> | --version <value>
            The version you want to upgrade / downgrade to. If not specified, will upagrade to latest version.
      --drop
            [DANGEROUS] Whether you want to first drop the database before migrating to the given version. WARNING! You will loose all your data, don't use this option in production!
      --help
            Display this schema manager usage.
    Example:
      docker run -it --rm -e ENV=dev dockerep-0.mtl.mnubo.com/reverse_geo:latest --version 0004

