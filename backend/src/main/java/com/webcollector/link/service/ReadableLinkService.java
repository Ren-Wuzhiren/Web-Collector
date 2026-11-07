package com.webcollector.link.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import com.webcollector.link.dto.LinkSummary;
import com.webcollector.link.dto.ReadableLinkResponse;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

/**
 * 读取当前用户链接清理正文的用例。
 */
@Service
public class ReadableLinkService {
    private final LinkMapper linkMapper;
    private final SessionTokenService sessionTokenService;

    public ReadableLinkService(LinkMapper linkMapper, SessionTokenService sessionTokenService) {
        this.linkMapper = linkMapper;
        this.sessionTokenService = sessionTokenService;
    }

    /**
     * 读取本人链接的清理正文。
     *
     * <p>[业务] 查询同时绑定 linkId 与当前 userId。链接不存在、不属于当前用户
     * 或正文尚未生成时统一返回 404，避免泄露其他用户链接是否存在。</p>
     */
    @Transactional(readOnly = true)
    public ReadableLinkResponse getReadableLink(Long linkId) {
        Long userId = sessionTokenService.requireLoginId();

        Link link = linkMapper.selectOne(
                new LambdaQueryWrapper<Link>()
                        .eq(Link::getId, linkId)
                        .eq(Link::getUserId, userId)
        );

        if (link == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        String textContent = link.getTextContent();
        if (textContent == null || textContent.isBlank()) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "正文尚未生成"
            );
        }

        return new ReadableLinkResponse(toSummary(link), textContent);
    }

    private LinkSummary toSummary(Link link) {
        return new LinkSummary(
                link.getId(),
                link.getCollectionId(),
                link.getOriginalUrl(),
                link.getTitle(),
                link.getDescription(),
                link.getCreatedAt().toInstant(ZoneOffset.UTC),
                link.getUpdatedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
