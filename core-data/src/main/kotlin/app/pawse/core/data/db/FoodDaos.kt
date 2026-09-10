package app.pawse.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodProductDao {

    /**
     * REPLACE, unlike every other insert in this app.
     *
     * A product row is a cache of somebody else's data, not a record of something
     * that happened to the user, so a fresher lookup should overwrite a staler one.
     * The log entries that referenced it keep their own frozen copy of the figures,
     * so nothing historical moves when this row does.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: FoodProductEntity): Long

    @Query("SELECT * FROM food_product WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): FoodProductEntity?

    @Query("SELECT * FROM food_product WHERE id = :id")
    suspend fun byId(id: Long): FoodProductEntity?

    /** Recently used foods, for logging something again without scanning it again. */
    @Query("SELECT * FROM food_product ORDER BY fetchedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 30): List<FoodProductEntity>

    @Query("SELECT * FROM food_product WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun search(query: String, limit: Int = 30): List<FoodProductEntity>

    @Query("SELECT COUNT(*) FROM food_product")
    suspend fun count(): Int
}

@Dao
interface FoodLogDao {

    @Insert
    suspend fun insert(entry: FoodLogEntryEntity): Long

    @Update
    suspend fun update(entry: FoodLogEntryEntity)

    @Delete
    suspend fun delete(entry: FoodLogEntryEntity)

    @Query("SELECT * FROM food_log_entry WHERE id = :id")
    suspend fun byId(id: Long): FoodLogEntryEntity?

    @Query("SELECT * FROM food_log_entry WHERE localDate = :date ORDER BY eatenAtEpochMs")
    fun observeDay(date: String): Flow<List<FoodLogEntryEntity>>

    @Query("SELECT * FROM food_log_entry WHERE localDate = :date ORDER BY eatenAtEpochMs")
    suspend fun day(date: String): List<FoodLogEntryEntity>

    @Query("SELECT * FROM food_log_entry WHERE localDate BETWEEN :from AND :to ORDER BY eatenAtEpochMs")
    suspend fun between(from: String, to: String): List<FoodLogEntryEntity>

    /** Entries not yet mirrored into Health Connect, for a retry after a denied grant. */
    @Query("SELECT * FROM food_log_entry WHERE healthConnectId IS NULL ORDER BY eatenAtEpochMs DESC LIMIT :limit")
    suspend fun notMirrored(limit: Int = 100): List<FoodLogEntryEntity>

    @Query("UPDATE food_log_entry SET healthConnectId = :recordId WHERE id = :id")
    suspend fun setHealthConnectId(id: Long, recordId: String?)
}
