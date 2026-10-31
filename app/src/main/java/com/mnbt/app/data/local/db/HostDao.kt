package com.mnbt.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {

    @Query("SELECT * FROM hosts ORDER BY created_at ASC")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observeById(id: Long): Flow<HostEntity?>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun findById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE base_url = :baseUrl LIMIT 1")
    suspend fun findByBaseUrl(baseUrl: String): HostEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE hosts SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String)

    @Query("UPDATE hosts SET pinned_fingerprint = :fingerprint WHERE id = :id")
    suspend fun updatePin(id: Long, fingerprint: String?)

    @Query(
        "UPDATE hosts SET overview_cache = :json, overview_cached_at = :cachedAt, " +
            "last_seen_at = :cachedAt WHERE id = :id",
    )
    suspend fun updateOverview(id: Long, json: String?, cachedAt: Long)

    @Query("UPDATE hosts SET last_seen_at = :at WHERE id = :id")
    suspend fun touchLastSeen(id: Long, at: Long)

    @Query("UPDATE hosts SET plugin_version = :version WHERE id = :id")
    suspend fun updatePluginVersion(id: Long, version: String?)

    @Query("UPDATE hosts SET needs_repair = :needsRepair WHERE id = :id")
    suspend fun updateNeedsRepair(id: Long, needsRepair: Boolean)
}
