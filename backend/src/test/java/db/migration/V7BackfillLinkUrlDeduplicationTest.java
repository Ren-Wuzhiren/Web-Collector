package db.migration;

import com.webcollector.link.support.UrlNormalizer;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V7BackfillLinkUrlDeduplicationTest {

    // [测试意图] 验证 V7 用运行时相同规则回填存量 URL 和 SHA-256。
    @Test
    void shouldBackfillNormalizedUrlAndHash() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(
                "SELECT id, user_id, original_url FROM link ORDER BY id"
        )).thenReturn(select);
        when(connection.prepareStatement(
                "UPDATE link SET normalized_url = ?, url_hash = ? WHERE id = ?"
        )).thenReturn(update);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong("id")).thenReturn(7L);
        when(rows.getLong("user_id")).thenReturn(3L);
        when(rows.getString("original_url"))
                .thenReturn("HTTPS://Example.COM:443#part");

        new V7__BackfillLinkUrlDeduplication().migrate(context);

        String normalizedUrl = "https://example.com/";
        verify(update).setString(1, normalizedUrl);
        verify(update).setString(2, UrlNormalizer.sha256(normalizedUrl));
        verify(update).setLong(3, 7L);
        verify(update).executeBatch();
    }

    // [测试意图] 验证用户内旧 URL 规范化后重复时迁移中止且不执行任何回填。
    @Test
    void shouldStopBeforeUpdatingWhenLegacyUrlsNormalizeToDuplicate() throws Exception {
        Context context = mock(Context.class);
        Connection connection = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);

        when(context.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(
                "SELECT id, user_id, original_url FROM link ORDER BY id"
        )).thenReturn(select);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getLong("id")).thenReturn(10L, 11L);
        when(rows.getLong("user_id")).thenReturn(3L);
        when(rows.getString("original_url"))
                .thenReturn(
                        "https://example.com/#first",
                        "HTTPS://EXAMPLE.COM:443/"
                );

        assertThatThrownBy(
                () -> new V7__BackfillLinkUrlDeduplication().migrate(context)
        )
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("link.id=10")
                .hasMessageContaining("link.id=11");

        verify(connection, never()).prepareStatement(
                "UPDATE link SET normalized_url = ?, url_hash = ? WHERE id = ?"
        );
    }
}
