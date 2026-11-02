package com.webcollector.capture.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webcollector.capture.asset.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;
import java.util.List;


@Mapper
public interface AssetMapper extends BaseMapper<Asset> {
    @Select("""
                SELECT a.*
                FROM asset a
                JOIN link l ON l.id = a.link_id
                WHERE a.id = #{assetId}
                  AND l.user_id = #{userId}
    """)
    Optional<Asset> findOwnedAsset(
            @Param("assetId") long assetId,
            @Param("userId") long userId
    );

    @Select("SELECT storage_path FROM asset WHERE link_id = #{linkId} ORDER BY id")
    List<String> selectStoragePathsByLink(@Param("linkId") long linkId);

    @Select("SELECT COUNT(*) FROM asset WHERE storage_path = #{storagePath}")
    int countByStoragePath(@Param("storagePath") String storagePath);

    @Select("""
            SELECT a.storage_path
            FROM asset a
            INNER JOIN link l ON l.id = a.link_id
            WHERE l.id = #{linkId}
              AND l.user_id = #{userId}
              AND a.asset_type = 'READABLE'
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT 1
            """)
    String selectLatestReadablePath(
            @Param("linkId") Long linkId,
            @Param("userId") Long userId
    );
}
