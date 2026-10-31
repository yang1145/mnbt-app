package com.mnbt.app.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SshSessionDao {

    @Query("SELECT * FROM ssh_sessions ORDER BY last_used_at DESC, created_at DESC")
    fun observeAll(): Flow<List<SshSessionEntity>>

    @Query("SELECT * FROM ssh_sessions WHERE id = :id")
    suspend fun findById(id: Long): SshSessionEntity?

    @Insert
    suspend fun insert(session: SshSessionEntity): Long

    @Update
    suspend fun update(session: SshSessionEntity)

    @Query("DELETE FROM ssh_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE ssh_sessions SET last_used_at = :at WHERE id = :id")
    suspend fun touchLastUsed(id: Long, at: Long)

    @Query("UPDATE ssh_sessions SET known_host_fingerprint = :fingerprint WHERE id = :id")
    suspend fun updateKnownHost(id: Long, fingerprint: String)
}
