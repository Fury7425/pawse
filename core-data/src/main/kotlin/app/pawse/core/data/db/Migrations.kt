package app.pawse.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations.
 *
 * Destructive migration is never an option in this app. The database holds the
 * only copy of a continuous record of the user's sleep and heart rate — there is
 * no account and no server to restore from — so a wrong migration is not an
 * inconvenience, it is the loss of the history every baseline is built on.
 *
 * The statements below are copied verbatim from the `createSql` Room exported into
 * `core-data/schemas`. That is not belt and braces: Room checks the live schema
 * against the exported one by identity hash at open time, and a migration that
 * creates a nearly-right table fails on the next launch rather than at review.
 */
object Migrations {

    /**
     * Adds the food log. Nothing existing is touched: two new tables and their
     * indices, so a user upgrading keeps every night they have recorded.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food_product` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`barcode` TEXT, " +
                    "`name` TEXT NOT NULL, " +
                    "`brand` TEXT, " +
                    "`kcalPer100g` REAL, " +
                    "`proteinPer100g` REAL, " +
                    "`carbsPer100g` REAL, " +
                    "`fatPer100g` REAL, " +
                    "`saturatedFatPer100g` REAL, " +
                    "`sugarPer100g` REAL, " +
                    "`fiberPer100g` REAL, " +
                    "`sodiumMgPer100g` REAL, " +
                    "`servingGrams` REAL, " +
                    "`servingLabel` TEXT, " +
                    "`source` TEXT NOT NULL, " +
                    "`sourceDetail` TEXT, " +
                    "`fetchedAtEpochMs` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_food_product_barcode` " +
                    "ON `food_product` (`barcode`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_product_name` ON `food_product` (`name`)",
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `food_log_entry` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`productId` INTEGER, " +
                    "`name` TEXT NOT NULL, " +
                    "`brand` TEXT, " +
                    "`localDate` TEXT NOT NULL, " +
                    "`eatenAtEpochMs` INTEGER NOT NULL, " +
                    "`zoneOffsetSeconds` INTEGER NOT NULL, " +
                    "`meal` TEXT NOT NULL, " +
                    "`quantityGrams` REAL NOT NULL, " +
                    "`servings` REAL, " +
                    "`kcal` REAL, " +
                    "`proteinGrams` REAL, " +
                    "`carbsGrams` REAL, " +
                    "`fatGrams` REAL, " +
                    "`saturatedFatGrams` REAL, " +
                    "`sugarGrams` REAL, " +
                    "`fiberGrams` REAL, " +
                    "`sodiumMilligrams` REAL, " +
                    "`source` TEXT NOT NULL, " +
                    "`healthConnectId` TEXT)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_log_entry_localDate` " +
                    "ON `food_log_entry` (`localDate`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_food_log_entry_productId` " +
                    "ON `food_log_entry` (`productId`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
