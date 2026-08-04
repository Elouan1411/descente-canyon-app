package fr.descentecanyon.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.descentecanyon.app.data.local.entity.ForumUserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ForumUserDao {

    @Query("SELECT * FROM forum_users ORDER BY username")
    fun observeAll(): Flow<List<ForumUserEntity>>

    @Query("SELECT * FROM forum_users WHERE username = :username")
    suspend fun getByUsername(username: String): ForumUserEntity?

    @Query("SELECT * FROM forum_users WHERE normalizedUsername = :normalizedUsername")
    suspend fun getByNormalizedUsername(normalizedUsername: String): ForumUserEntity?

    @Query("SELECT * FROM forum_users WHERE normalizedUsername LIKE '%' || :query || '%' OR username LIKE '%' || :query || '%' ORDER BY username LIMIT :limit")
    suspend fun search(query: String, limit: Int = 50): List<ForumUserEntity>

    @Query("SELECT COUNT(*) FROM forum_users")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<ForumUserEntity>)

    @Query("DELETE FROM forum_users")
    suspend fun clearAll()
}
