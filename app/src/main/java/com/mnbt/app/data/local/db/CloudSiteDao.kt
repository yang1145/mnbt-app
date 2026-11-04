package com.mnbt.app.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudSiteDao {

    /** 新站点插入（id=0）；同地址重扫的原地换绑走整行 update（id 已带） */
    @Upsert
    suspend fun upsert(site: CloudSiteEntity): Long

    @Query("SELECT * FROM cloud_sites ORDER BY paired_at ASC, id ASC")
    fun observeAll(): Flow<List<CloudSiteEntity>>

    @Query("SELECT * FROM cloud_sites WHERE id = :id")
    suspend fun getById(id: Long): CloudSiteEntity?

    @Query("SELECT * FROM cloud_sites WHERE base_url = :baseUrl LIMIT 1")
    suspend fun findByBaseUrl(baseUrl: String): CloudSiteEntity?

    @Query("UPDATE cloud_sites SET token_ref = :tokenRef WHERE id = :id")
    suspend fun updateToken(id: Long, tokenRef: String)

    @Query("UPDATE cloud_sites SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE cloud_sites SET capabilities_json = :capabilitiesJson WHERE id = :id")
    suspend fun updateCapabilities(id: Long, capabilitiesJson: String?)

    @Query("UPDATE cloud_sites SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("DELETE FROM cloud_sites WHERE id = :id")
    suspend fun delete(id: Long)
}
