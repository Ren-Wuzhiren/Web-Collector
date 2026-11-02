package com.webcollector.search.service;

import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.link.dto.LinkSummary;
import com.webcollector.link.entity.Link;
import com.webcollector.link.mapper.LinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

@Service
public class LinkSearchService {
    private final LinkMapper linkMapper;
    private final SessionTokenService sessionTokenService;

    public LinkSearchService(LinkMapper linkMapper, SessionTokenService sessionTokenService) {
        this.linkMapper = linkMapper;
        this.sessionTokenService = sessionTokenService;
    }

    @Transactional(readOnly = true)
    public List<LinkSummary> search(String query) {
        String normalizedQuery = query.trim();
        Long userId = sessionTokenService.requireLoginId();

        return linkMapper.searchOwnedLinks(userId, normalizedQuery)
                .stream()
                .map(this::toSummary)
                .toList();

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
