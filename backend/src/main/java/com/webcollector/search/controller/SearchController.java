package com.webcollector.search.controller;

import com.webcollector.common.web.ApiResponse;
import com.webcollector.link.dto.LinkSummary;
import com.webcollector.search.service.LinkSearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public ApiResponse<List<LinkSummary>> search(
            @RequestParam("q")
            @NotBlank(message = "搜索词不能为空")
            @Size(max = 200, message = "搜索词不能超过 200 个字符")
            String query
    ) {
        return ApiResponse.success(linkSearchService.search(query));
    }
}
