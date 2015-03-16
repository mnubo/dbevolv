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

The `db.conf` should contain which database the schema is for. See "Supported databases" for valid values. You must also specify a logical name. Example:

    database_kind = cassandra
    schema_name = reverse_geo

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

Example
-------

[cassandra-reverse-geocoding](http://git-lab1.mtl.mnubo.com/mnubo/cassandra-reverse-geocoder/tree/master)

Upgrading / downgrading a database
----------------------------------

The target schema/database/keyspace must already exist. dbschemas do not support the creation of the database itself.

Command line:

    docker run -it --rm docker.mnubo.com/<schema_name>:latest <hosts> <port> <username> <pwd> <schema/database/keyspace> [<version>]"

Some of those arguments might not be necessary for some kind of databases. For example, `<port>`, `<username>`, and `<pwd>` are ignored by the Cassandra database. Just pass '0' in that case. Ex:

    docker run -it --rm docker.mnubo.com/reverse_geo:latest atca-mnu1-s06.mtl.mnubo.com,atca-mnu1-s09.mtl.mnubo.com,atca-mnu1-s13.mtl.mnubo.com 0 0 0 mnuboglobalconfig"

Version is the version you want to upgrade / downgrade to. If ommited, the database will be upgraded to the latest version.
