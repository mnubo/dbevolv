import com.mnubo.app_util.Logging
import com.mnubo.dbschemas._
import org.scalatest.time.SpanSugar
import org.scalatest.{Matchers, WordSpec}

class DatabaseMigratorSpec extends WordSpec with Matchers with SpanSugar with Logging {
  val Migration1 = Migration("1", isRebase = false)
  val Migration2 = Migration("2", isRebase = false)
  val Migration3 = Migration("3", isRebase = false)
  val MigrationR10 = Migration("R1.0", isRebase = true)
  val MigrationR11 = Migration("1.1", isRebase = false)
  val MigrationR12 = Migration("1.2", isRebase = false)
  val MigrationR2 = Migration("R2", isRebase = true)
  val MigrationR21 = Migration("2.1", isRebase = false)
  val MigrationR22 = Migration("2.2", isRebase = false)

  val InstalledVersion1 = InstalledVersion("1", null, null)
  val InstalledVersion2 = InstalledVersion("2", null, null)
  val InstalledVersion3 = InstalledVersion("3", null, null)
  val InstalledVersionR10 = InstalledVersion("R1.0", null, null)
  val InstalledVersionR11 = InstalledVersion("1.1", null, null)
  val InstalledVersionR12 = InstalledVersion("1.2", null, null)
  val InstalledVersionR2 = InstalledVersion("R2", null, null)
  val InstalledVersionR21 = InstalledVersion("2.1", null, null)
  val InstalledVersionR22 = InstalledVersion("2.2", null, null)
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
          val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
          val targetVersion = Some(Migration3.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "3", skipSchemaVerification = true, Seq(Migration1, Migration2, Migration3), UpgradeOperation)

          actual shouldEqual expected
        }

        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = true, Seq(Migration1, Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "1.2", skipSchemaVerification = true, Seq(MigrationR10, MigrationR11, MigrationR12), UpgradeOperation)

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
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
        "upgrade to X" in {
          val targetVersion = Some(Migration2.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "2", skipSchemaVerification = false, Seq(Migration2), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1", "1.2", skipSchemaVerification = false, Seq(Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12), UpgradeOperation)

          actual shouldEqual expected
        }
      }
    }

    "determine upgrade versions properly from a starting point after a rebase" should {
      val installedVersion = Set(InstalledVersionR11)
      "without rebase" should {
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
        "upgrade to X" in {
          val targetVersion = Some(MigrationR12.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1.1", "1.2", skipSchemaVerification = false, Seq(MigrationR12), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1.1", "1.2", skipSchemaVerification = false, Seq(MigrationR12), UpgradeOperation)

          actual shouldEqual expected
        }
      }
      "with rebase" should {
        val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12, MigrationR2, MigrationR21, MigrationR22)
        "upgrade to X" in {
          val targetVersion = Some(MigrationR12.version)

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1.1", "1.2", skipSchemaVerification = false, Seq(MigrationR12), UpgradeOperation)

          actual shouldEqual expected
        }

        "upgrade to latest" in {
          val targetVersion = None

          val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
          val expected = MigrationSpec("1.1", "2.2", skipSchemaVerification = false, Seq(MigrationR12, MigrationR2, MigrationR21, MigrationR22), UpgradeOperation)

          actual shouldEqual expected
        }
      }
    }


    "determine versions properly in when the starting point is a rebase" in {
      val installedVersion = NoInstalls
      val availableMigrations = Seq(MigrationR10, MigrationR11, MigrationR12)
      val targetVersion = Some(MigrationR11.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("R1.0", "1.1", skipSchemaVerification = true, Seq(MigrationR10, MigrationR11), UpgradeOperation)

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
      val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
      val targetVersion = Some(Migration1.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("2", "1", skipSchemaVerification = false, Seq(Migration2), DowngradeOperation)

      actual shouldEqual expected
    }

    "determine versions properly in downgrade when one rebase is available post rebase" in {
      val installedVersion = Set(InstalledVersionR10, InstalledVersionR11, InstalledVersionR12)
      val availableMigrations = Seq(Migration1, Migration2, Migration3, MigrationR10, MigrationR11, MigrationR12)
      val targetVersion = Some(MigrationR11.version)

      val actual = DatabaseMigrator.getMigrationToApply(targetVersion, availableMigrations, installedVersion)
      val expected = MigrationSpec("1.2", "1.1", skipSchemaVerification = false, Seq(MigrationR12), DowngradeOperation)

      actual shouldEqual expected
    }

  }

}