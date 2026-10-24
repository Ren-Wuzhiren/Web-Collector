package com.webcollector.capture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.capture.entity.CaptureJob;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * [软件工程] 普通持久化操作复用 BaseMapper；需要行锁、SKIP LOCKED 和
 * 字段原子增减的领取操作使用这里声明的原生 SQL。
 *
 * [排错] 不写 @Mapper 注解也能量：MybatisPlusConfig 上的
 * @MapperScan("com.webcollector.**.mapper") 会扫到这个接口。
 */
public interface CaptureJobMapper extends BaseMapper<CaptureJob> {
    /**
     * [业务] 查询下一条已经到期、可以领取的任务，并锁住这一行。
     *
     * [数据库] FOR UPDATE 锁到事务结束；SKIP LOCKED 让其他 Worker
     * 遇到已锁定行时直接跳过，而不是排队等待。
     *
     * [排错] LIMIT 必须写在 FOR UPDATE 前面，否则 MySQL 会报语法错误。
     */
    @Select(
            """
            SELECT id
            FROM capture_job
            WHERE status = 'PENDING'
                AND next_attempt_at <= #{now}
            ORDER BY next_attempt_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    Long selectNextClaimableJobId(@Param("now") LocalDateTime now);

    /**
     * [业务] 把已经选中的 PENDING 任务改成 RUNNING，并登记本次领取。
     *
     * [数据库] attempt_count 在数据库里自增，避免“先读再写”造成丢失更新。
     * WHERE 继续检查 status='PENDING'，作为状态机的第二道保护。
     */
    @Update(
            """
            UPDATE capture_job
            SET status = 'RUNNING',
                attempt_count = attempt_count + 1,
                locked_by = #{workerId},
                locked_until = #{lockedUntil},
                started_at = #{now},
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'PENDING'
            """
    )
    int claimPendingJob(
            @Param("jobId") Long jobId,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );
}
