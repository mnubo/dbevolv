import com.mnubo.app_util.Logging
import com.mnubo.dbschemas._
import org.scalatest.time.SpanSugar
import org.scalatest.{Matchers, WordSpec}

class DatabaseMigratorSpec extends WordSpec with Matchers with SpanSugar with Logging {
  val Migration1 = Migration("1", isRebase = false)
  val Migration2 = Migration("2", isRebase = false)
  val Migration3 = Migration("3", isRebase = false)
  val MigrationR4 = Migration("R4.0", isRebase = true)
  val Migration41 = Migration("4.1", isRebase = false)
  val Migration42 = Migration("4.2", isRebase = false)
  val MigrationR5 = Migration("R5.0", isRebase = true)
  val Migration51 = Migration("5.1", isRebase = false)
  val Migration52 = Migration("5.2", isRebase = false)

  val InstalledVersion1 = InstalledVersion("1", null, null)
  val InstalledVersion2 = InstalledVersion("2", null, null)
  val InstalledVersion3 = InstalledVersion("3", null, null)
  val InstalledVersionR4 = InstalledVersion("R4.0", null, null)
  val InstalledVersion41 = InstalledVersion("4.1", null, null)
  val InstalledVersion42 = InstalledVersion("4.2", null, null)
  val InstalledVersionR5 = InstalledVersion("R5.0", null, null)
  val InstalledVersion51 = InstalledVersion("5.1", null, null)
  val InstalledVersion52 = InstalledVersion("5.2", null, null)
  val NoInstalls = Set.empty[InstalledVersion]

  val DefaultNamespace = "ingestion"
  "The DatabaseMigrator" should {

    "determine upgrade versions properly from scratch" should {
      val installedVersion = NoInstalls
      "without rebase" should {

        "upgrade to X + 1" in {
          val availableMigrations = Seq(Migration1, Migration2, Migration3)
          val targetVersion = Some(Migration3.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "3", skipSchemaVerification = true, Seq(Migration1, Migration2, Migration3), UpgradeOperation)

          actual shouldEqual expected
        }

        val availableMigrations = Seq(Migration1, Migration2)

        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = true, Seq(Migration1, Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = true, Seq(Migration1, Migration2), UpgradeOperation)

          actual shouldEqual expected
        }
      }
      "with rebase" should {
        "upgrade to X + 1" in {
          val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
          val targetVersion = Some(Migration3.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "3", skipSchemaVerification = true, Seq(Migration1, Migration2, Migration3), UpgradeOperation)

          actual shouldEqual expected
        }

        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = true, Seq(Migration1, Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "4.2", skipSchemaVerification = true, Seq(MigrationR4, Migration41, Migration42), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest from rebase" in {
          val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4)
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "R4.0", skipSchemaVerification = true, Seq(MigrationR4), UpgradeOperation)

          actual shouldEqual expected
        }

      }
    }

    "determine upgrade versions properly from a starting point before a rebase" should {
      val installedVersion = Set(InstalledVersion1)
      "without rebase" should {
        val availableMigrations = Seq(Migration1, Migration2)

        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = false, Seq(Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = false, Seq(Migration2), UpgradeOperation)

          actual shouldEqual expected
        }
      }
      "with rebase" should {
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = false, Seq(Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "4.2", skipSchemaVerification = false, Seq(Migration2, Migration3, MigrationR4, Migration41, Migration42), UpgradeOperation)

          actual shouldEqual expected
        }
      }
    }

    "determine upgrade versions properly from a starting point after a rebase" should {
      val installedVersion = Set(InstalledVersion41)
      "without rebase" should {
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
        "upgrade to X" in {
          val targetVersion = Some(Migration42.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("4.1", "4.2", skipSchemaVerification = false, Seq(Migration42), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("4.1", "4.2", skipSchemaVerification = false, Seq(Migration42), UpgradeOperation)

          actual shouldEqual expected
        }
      }
      "with rebase" should {
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42, MigrationR5, Migration51, Migration52)
        "upgrade to X" in {
          val targetVersion = Some(Migration42.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("4.1", "4.2", skipSchemaVerification = false, Seq(Migration42), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("4.1", "5.2", skipSchemaVerification = false, Seq(Migration42, MigrationR5, Migration51, Migration52), UpgradeOperation)

          actual shouldEqual expected
        }
      }
    }


    "determine versions properly in when the starting point is a rebase" in {
      val installedVersion = NoInstalls
      val availableMigrations = Seq(MigrationR4, Migration41, Migration42)
      val targetVersion = Some(Migration41.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("R4.0", "4.1", skipSchemaVerification = true, Seq(MigrationR4, Migration41), UpgradeOperation)

      actual shouldEqual expected
    }

    "determine versions properly in downgrade" in {
      val installedVersion = Set(InstalledVersion1, InstalledVersion2)
      val availableMigrations = Seq(Migration1, Migration2)
      val targetVersion = Some(Migration1.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("2", "1", skipSchemaVerification = false, Seq(Migration2), DowngradeOperation)

      actual shouldEqual expected
    }

    "determine versions properly in downgrade when one rebase is available" in {
      val installedVersion = Set(InstalledVersion1, InstalledVersion2)
      val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
      val targetVersion = Some(Migration1.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("2", "1", skipSchemaVerification = false, Seq(Migration2), DowngradeOperation)

      actual shouldEqual expected
    }

    "determine versions properly in downgrade when one rebase is available post rebase" in {
      val installedVersion = Set(InstalledVersionR4, InstalledVersion41, InstalledVersion42)
      val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR4, Migration41, Migration42)
      val targetVersion = Some(Migration41.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("4.2", "4.1", skipSchemaVerification = false, Seq(Migration42), DowngradeOperation)

      actual shouldEqual expected
    }

  }

}