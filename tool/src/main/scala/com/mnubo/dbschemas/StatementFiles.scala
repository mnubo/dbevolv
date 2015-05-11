package com.mnubo.dbschemas

import java.io.{FileFilter, File}

import com.mnubo.app_util.Logging

import scala.io.Source

object StatementFiles extends Logging {
  def parseStatements(stmtFile: File): Seq[Statement] =
    Source
      .fromFile(stmtFile)
      .getLines()
      .filterNot(_.trim.isEmpty)
      .filterNot(_.startsWith("#"))
      .mkString(" ")
      .split(";")
      .toSeq
      .map(_.trim)
      .filterNot(_.isEmpty) // mkString is at least producing an empty string, so have to refilter empty lines.
      .map { line =>
        if (line.startsWith("@@"))
          ClassStatement(line.replace("@@", ""))
        else
          StringStatement(line)
      }

  def findStatementFile(version: String, stmtType: String) =
    new File(s"migrations/$version")
      .listFiles(new FileFilter {
      override def accept(pathname: File) =
        pathname.isFile && !pathname.isHidden
    })
      .find(_.getName.startsWith(stmtType))
      .get

}

sealed trait Statement {
  def execute(conn: DatabaseConnection, databaseName: String)
}

case class StringStatement(statementText: String) extends Statement {
  override def execute(conn: DatabaseConnection, databaseName: String) =
    conn.execute(statementText)
}

case class ClassStatement(className: String) extends Statement {
  private val c = getClass.getClassLoader.loadClass(className)
  private lazy val scripInstance = c.newInstance()
  private lazy val executeMethod = c.getMethods.find(_.getName == "execute").get

  lazy val sourceFile = {
    val subTree = c.getPackage.getName.replace(".", "/") + "/" + c.getSimpleName
    val candidates = List(s"src/main/scala/$subTree.scala", s"src/main/java/$subTree.java")
    candidates.find(new File(_).exists())
  }

  override def execute(conn: DatabaseConnection, databaseName: String) =
    executeMethod.invoke(scripInstance, conn.innerConnection, databaseName)
}
