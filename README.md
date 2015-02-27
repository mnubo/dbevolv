dbschemas
=========

Allows to upgrade / downgrade our database instances to a particular version.

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
    /project/plugins.sbt
            /build.properties
    /migrations/0001/
               /0002/
               /0003/

The `db.conf` should contain which database the schema is for. See "Supported databases" for valid values. Example:

    database_kind = cassandra

The `build.sbt` should contain the name of the schema. It will be used as the base name for the Docker container. Example:

    name := "reverse-geo"

The `build.properties` file should contain which SBT version to use:

    sbt.version=0.13.7

The `plugins.sbt` should point to this plugin on Artifactory:

    resolvers += "Mnubo release repository" at "http://artifactory.mtl.mnubo.com:8081/artifactory/libs-release-local/"

    addSbtPlugin("com.mnubo" % "dbschemas-sbt-plugin" % "1.0.0")

The directories names in `/migrations` constitute the migration versions. Migrations will be applied in the lexical order of those directory names. Ex: when asking dbschema to upgrade to version '0002' in the above example, '0001' will be executed first, then '0002'.

Migration directories must contain 2 files named `upgrade.???` and `downgrade.???`. The extension depend on the database type. For example, for Cassandra:

    /migrations/0001/upgrade.cql
                    /downgrade.cql

The upgrade file should contain what it takes to upgrade a given database to the given version. The downgrade file should contain what it takes to downgrade from the given version to the previous version.

Each statement can be laid out on multiple lines, and be terminated by a ';' character. Lines starting with a '#' character will be considered as comments and will be ignored. Empty lines are ignored.

Upgrading / downgrading a database
----------------------------------

The target schema/database/keyspace must already exist. dbschemas do not support the creation of the database itself.

Command line:

    sbt "run <hosts> <port> <username> <pwd> <schema/database/keyspace> [<version>]"

Some of those arguments might not be necessary for some kind of databases. For example, `<port>`, `<username>`, and `<pwd>` are ignored by the Cassandra database. Just pass '0' in that case. Ex:

    sbt "run atca-mnu1-s06.mtl.mnubo.com,atca-mnu1-s09.mtl.mnubo.com,atca-mnu1-s13.mtl.mnubo.com 0 0 0 mnuboglobalconfig"

Version is the version you want to upgrade / downgrade to. If ommited, the database will be upgraded to the latest version.
