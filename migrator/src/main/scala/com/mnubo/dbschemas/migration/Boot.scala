package com.mnubo
package dbschemas.migration

import java.io.{PrintStream, FileInputStream, InputStreamReader, File}
import java.text.SimpleDateFormat
import java.util.Date

import com.google.gson.{JsonObject, JsonElement, JsonParser}

import collection.JavaConverters._

object Boot extends App {
  val parser = new JsonParser
  val Ingestion = "ingestion"
  val Enrichment = "enrichment"
  val Analytics = "analytics"
  val GlobalConfig = "mnuboglobalconfig"
  val projects = List(Ingestion, Enrichment, Analytics, GlobalConfig)
  val TableSpec = """([a-z_\-0-9\.]+)"""
  val CreateTable1Regex = s"""(?i)create table if not exists $TableSpec .+""".r
  val CreateTable2Regex = s"""(?i)create table $TableSpec .+""".r
  val UpdateRegex = s"""(?i)update $TableSpec .+""".r
  val TruncateRegex = s"""(?i)truncate $TableSpec;""".r
  val AlterTableRegex = s"""(?i)alter table $TableSpec .+""".r
  val InsertIntoRegex = s"""(?i)insert into $TableSpec .+""".r
  val DropTable1Regex = s"""(?i)drop table $TableSpec;""".r
  val DropTable2Regex = s"""(?i)drop table if exists $TableSpec;""".r
  val DropTable3Regex = s"""(?i)drop $TableSpec;""".r
  val DeleteRegex = s"""(?i)delete from $TableSpec .+""".r
  val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'")
  val now = df.format(new Date())

  val migrationLegacyFiles = new File("../mnubo-main/platform/schemamanager/app/src/main/resources/mnuboSchemas")
    .listFiles()
    .toSeq
    .filter(_.getName.endsWith(".json"))
    .sortBy(_.getName)

  val projectTableMapping = Map(
    "rta_per_state" -> Analytics,
    "objects_per_state" -> Analytics,
    "object_state" -> Analytics,
    "rta_numeric_results" -> Analytics,
    "rta_groupby_results" -> Analytics,
    "analytics_objects_with_imported_attributes" -> Enrichment
  ).withDefaultValue(Ingestion)

  val versions = migrationLegacyFiles.map { migrationFile =>
    val json = using(new InputStreamReader(new FileInputStream(migrationFile)))(parser.parse)
    val upgradeScript = getScript(json.getAsJsonObject, "UpgradeDelta")
    val downgradeScript = getScript(json.getAsJsonObject, "DowngradeDelta")
    var schemaScripts = Map(Ingestion -> Migration(), Enrichment -> Migration(), Analytics -> Migration(), GlobalConfig -> Migration())

    def splitInProjects(script: Seq[String], addCommand: (Migration, String) => Migration) =
      script.foreach { cmd =>
        val tableName = parseTableName(cmd)
        val project =
          if (tableName.isEmpty)
            Ingestion
          else if (tableName.startsWith("mnuboglobalconfig."))
            GlobalConfig
          else
            projectTableMapping(tableName)

        val script = schemaScripts(project)
        schemaScripts = schemaScripts.updated(project, addCommand(script, cmd))
      }

    splitInProjects(upgradeScript, _ addUpgrade _)
    splitInProjects(downgradeScript, _ addDowngrade _)

    val version = migrationFile.getName.replace("_MnuboSchema.json", "").replace(".", "_")
    println("Version " + version)


    schemaScripts
      .filterNot(_._2.isEmpty)
      .foreach { case (name, s) =>
        s.print(name)

        if (args.isEmpty) {
          val projectname = s"cassandra-$name"
          val migrationPath = new File(s"../$projectname/migrations/$version")
          if (!migrationPath.exists()) migrationPath.mkdirs()
          s.export(migrationPath)
        }
      }

    version
  }

  def cql(template: String => String) =
    projects.map(p => s"${template(if (p == GlobalConfig) s"mnuboglobalconfig.$p" else p)}").mkString(",\n      ")

  def inserts(projectName: String) =
    versions.map(v => s""""INSERT INTO ${projectName}_version (migration_version, migration_date) VALUES ('$v', '$now')"""").mkString(",\n      ")

    using(new PrintStream("0.5.0.0_MnuboSchema.json")) { p =>
      p.println(
        s"""{
           |  "UpgradeDelta": {
           |    "Tables": [
           |      ${cql(p => s""""CREATE TABLE ${p}_version (migration_version TEXT, migration_date TIMESTAMP, PRIMARY KEY (migration_version))"""")}
           |    ],
           |    "Records": [
           |      ${cql(inserts)}
           |    ]
           |  },
           |  "DowngradeDelta": {
           |    "Tables": [
           |      ${cql(p => s""""TRUNCATE ${p}_version"""")},
           |      ${cql(p => s""""DROP TABLE IF EXISTS ${p}_version"""")},
           |    ]
           |  }
           |}
           |""".stripMargin)
    }


  def parseTableName(cmd: String) = cmd match {
    case CreateTable1Regex(table) => table
    case CreateTable2Regex(table) => table
    case UpdateRegex(table) => table
    case TruncateRegex(table) => table
    case AlterTableRegex(table) => table
    case InsertIntoRegex(table) => table
    case DropTable1Regex(table) => table
    case DropTable2Regex(table) => table
    case DropTable3Regex(table) => table
    case DeleteRegex(table) => table
    case clazz if clazz.startsWith("@@") => ""
    case _ => throw new Exception(s"Cannot parse command '$cmd'")
  }

  def getScript(json: JsonObject, sectionName: String) =
    getSQLCommands(json, sectionName, "Tables") ++
    getSQLCommands(json, sectionName, "Records") ++
    getSQLCommands(json, sectionName, "Classes").map("@@" + _)

  def getSQLCommands(json: JsonObject, sectionName: String, subSectionName: String) =
    if (json.has(sectionName) && json.get(sectionName).getAsJsonObject.has(subSectionName))
      json.get(sectionName).getAsJsonObject
        .get(subSectionName).getAsJsonArray
        .iterator().asScala
        .toSeq
        .map(_.getAsString + ";")
    else if (sectionName == "UpgradeDelta" && json.has(subSectionName)) // The damn 0.0.0.1 migration script has no UpgradeDelta / DowngradeDelta, it has the Tables subsection directly at the json root.
      json.get(subSectionName).getAsJsonArray
        .iterator().asScala
        .toSeq
        .map(_.getAsString + ";")
    else
      Nil
}

case class Migration(upgrade: Seq[String] = Nil, downgrade: Seq[String] = Nil) {
  def addUpgrade(cmd: String) = copy(upgrade = upgrade :+ cmd)
  def addDowngrade(cmd: String) = copy(downgrade = downgrade :+ cmd)

  def isEmpty = upgrade.isEmpty && downgrade.isEmpty

  def print(name: String) = {
    println(name + ":")
    println("  Upgrade:")
    upgrade.foreach(cmd => println("    " + cmd))
    println("  Downgrade:")
    downgrade.foreach(cmd => println("    " + cmd))
    println()
  }

  def export(dir: File) = {
    if (!upgrade.isEmpty)
      using(new PrintStream(new File(dir, "upgrade.cql"))) { p =>
        upgrade.foreach(p.println)
      }
    if (!downgrade.isEmpty)
      using(new PrintStream(new File(dir, "downgrade.cql"))) { p =>
        downgrade.foreach(p.println)
      }
  }
}
