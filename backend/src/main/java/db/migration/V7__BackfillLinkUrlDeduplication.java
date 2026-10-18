package db.migration;

import com.webcollector.link.support.UrlNormalizer;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [软件工程] 将已有链接按当前 URL 规则补齐规范化地址和哈希。
 * 先检查所有旧记录，避免无效 URL 或重复项导致迁移写入一半。
 */
public class V7__BackfillLinkUrlDeduplication extends BaseJavaMigration {

    private record LegacyLink(Long id, Long userId, String originalUrl) {
    }

    private record NormalizedLink(Long id, String normalizedUrl, String urlHash) {
    }

    @Override
    public void migrate(Context context) throws Exception {
        List<LegacyLink> links = readLinks(context);
        Map<String, Long> firstLinkByOwnerAndHash = new HashMap<>();
        List<NormalizedLink> normalizedValues = new ArrayList<>(links.size());

        for (LegacyLink link : links) {
            String normalizedUrl;
            try {
                normalizedUrl = UrlNormalizer.normalize(link.originalUrl());
            } catch (RuntimeException exception) {
                throw new FlywayException(
                        "link.id=" + link.id()
                                + " 的 original_url 无法按当前规则迁移；请先修正该记录",
                        exception
                );
            }

            String urlHash = UrlNormalizer.sha256(normalizedUrl);
            String ownerAndHash = link.userId() + ":" + urlHash;
            Long duplicateId = firstLinkByOwnerAndHash.putIfAbsent(
                    ownerAndHash,
                    link.id()
            );
            if (duplicateId != null) {
                throw new FlywayException(
                        "link.id=" + link.id() + " 与 link.id=" + duplicateId
                                + " 规范化后重复；请先处理重复记录再重试迁移"
                );
            }

            normalizedValues.add(new NormalizedLink(
                    link.id(),
                    normalizedUrl,
                    urlHash
            ));
        }

        try (PreparedStatement update = context.getConnection().prepareStatement(
                "UPDATE link SET normalized_url = ?, url_hash = ? WHERE id = ?"
        )) {
            for (NormalizedLink values : normalizedValues) {
                update.setString(1, values.normalizedUrl());
                update.setString(2, values.urlHash());
                update.setLong(3, values.id());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private List<LegacyLink> readLinks(Context context) throws SQLException {
        List<LegacyLink> links = new ArrayList<>();
        try (PreparedStatement select = context.getConnection().prepareStatement(
                "SELECT id, user_id, original_url FROM link ORDER BY id"
        ); ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                links.add(new LegacyLink(
                        rows.getLong("id"),
                        rows.getLong("user_id"),
                        rows.getString("original_url")
                ));
            }
        }
        return links;
    }
}
