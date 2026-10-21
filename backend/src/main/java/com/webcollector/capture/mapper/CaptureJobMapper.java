package com.webcollector.capture.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.capture.entity.CaptureJob;

/**
 * [软件工程] 本步只需要 BaseMapper 的 insert；领取、改状态这些带条件的操作
 * 留到 M3 再按需扩展，现在不提前写用不到的方法。
 *
 * [排错] 不写 @Mapper 注解也能量：MybatisPlusConfig 上的
 * @MapperScan("com.webcollector.**.mapper") 会扫到这个接口。
 */
public interface CaptureJobMapper extends BaseMapper<CaptureJob> {

}
