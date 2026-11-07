package com.webcollector.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import com.webcollector.capture.entity.CaptureJob;

@Mapper
public interface AdminMapper {

    @Select("""
            SELECT *
            FROM capture_job
            WHERE id = #{jobId}
            """)
    CaptureJob findCaptureJob(@Param("jobId") long jobId);

    @Select("""
            SELECT *
            FROM capture_job
            WHERE id = #{jobId}
            FOR UPDATE
            """)
    CaptureJob lockCaptureJob(@Param("jobId") long jobId);

    @Select("""
            SELECT id
            FROM link
            WHERE id = #{linkId}
            FOR UPDATE
            """)
    Long lockLink(@Param("linkId") long linkId);

    @Select("""
            SELECT id
            FROM capture_job
            WHERE link_id = #{linkId}
              AND id > #{sourceJobId}
            ORDER BY id DESC
            LIMIT 1
            FOR UPDATE
            """)
    Long lockNewerJob(
            @Param("linkId") long linkId,
            @Param("sourceJobId") long sourceJobId
    );

    @Select("""
            SELECT
                j.id AS job_id,
                j.link_id,
                u.username AS owner_username,
                l.original_url,
                j.attempt_count,
                j.max_attempts,
                j.error_code,
                j.created_at,
                j.finished_at
            FROM capture_job j
            INNER JOIN link l ON l.id = j.link_id
            INNER JOIN app_user u ON u.id = l.user_id
            WHERE j.status = 'FAILED'
              AND j.id = (
                  SELECT MAX(latest.id)
                  FROM capture_job latest
                  WHERE latest.link_id = j.link_id
              )
              AND (#{beforeId} IS NULL OR j.id < #{beforeId})
            ORDER BY j.id DESC
            LIMIT #{limit}
            """)
    List<AdminFailedJobRow> findLatestFailedJobs(
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit
    );
}
