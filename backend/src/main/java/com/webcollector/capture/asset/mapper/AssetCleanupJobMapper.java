package com.webcollector.capture.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.capture.asset.entity.AssetCleanupJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AssetCleanupJobMapper extends BaseMapper<AssetCleanupJob> {
    @Insert("""
            INSERT IGNORE INTO asset_cleanup_job
                (storage_path, storage_path_hash, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES
                (#{storagePath}, SHA2(#{storagePath}, 256), 0, #{now}, #{now}, #{now})
            """)
    int enqueueIfAbsent(
            @Param("storagePath") String storagePath,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT id, storage_path, attempt_count, next_attempt_at,
                   locked_until, last_error, created_at, updated_at
            FROM asset_cleanup_job
            WHERE next_attempt_at <= #{now}
              AND (locked_until IS NULL OR locked_until <= #{now})
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    AssetCleanupJob selectNextClaimable(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE asset_cleanup_job
            SET attempt_count = attempt_count + 1,
                locked_until = #{lockedUntil},
                updated_at = #{now}
            WHERE id = #{id}
              AND (locked_until IS NULL OR locked_until <= #{now})
            """)
    int claim(
            @Param("id") Long id,
            @Param("now") LocalDateTime now,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );

    @Delete("DELETE FROM asset_cleanup_job WHERE id = #{id}")
    int deleteCompleted(@Param("id") Long id);

    @Update("""
            UPDATE asset_cleanup_job
            SET next_attempt_at = #{nextAttemptAt},
                locked_until = NULL,
                last_error = #{lastError},
                updated_at = #{now}
            WHERE id = #{id}
            """)
    int scheduleRetry(
            @Param("id") Long id,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now
    );
}
