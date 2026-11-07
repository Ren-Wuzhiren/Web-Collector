package com.webcollector.search.controller;

import com.webcollector.common.web.ApiResponse;
import com.webcollector.search.dto.SearchPage;
import com.webcollector.search.dto.SearchRequest;
import com.webcollector.search.service.LinkSearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private final LinkSearchService linkSearchService;

    public SearchController(LinkSearchService linkSearchService) {
        this.linkSearchService = linkSearchService;
    }

    @GetMapping
    public ApiResponse<SearchPage> search(
            @RequestParam(value = "q", required = false)
            @Size(max = 200, message = "搜索词不能超过 200 个字符")
            String query,
            @RequestParam(value = "collectionId", required = false)
            @Positive(message = "collectionId 必须为正数")
            Long collectionId,
            @RequestParam(value = "tagId", required = false)
            List<@Positive(message = "tagId 必须为正数") Long> tagIds,
            @RequestParam(value = "from", required = false)
            Instant from,
            @RequestParam(value = "to", required = false)
            Instant to,
            @RequestParam(value = "cursor", required = false)
            @Size(max = 4096, message = "搜索游标过长")
            String cursor,
            @RequestParam(value = "limit", defaultValue = "20")
            @Min(value = 1, message = "limit 不能小于 1")
            @Max(value = 100, message = "limit 不能大于 100")
            int limit
    ) {
        return ApiResponse.success(linkSearchService.search(
                new SearchRequest(
                        query,
                        collectionId,
                        tagIds,
                        from,
                        to,
                        cursor,
                        limit
                )
        ));
    }
}