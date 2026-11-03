package com.webcollector.capture.asset.controller;

import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.capture.asset.dto.AssetSummary;
import com.webcollector.capture.asset.service.AssetService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.webcollector.common.web.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
    private final AssetService assetService;
    private final SessionTokenService sessionTokenService;

    public AssetController(
            AssetService assetService,
            SessionTokenService sessionTokenService
    ) {
        this.assetService = assetService;
        this.sessionTokenService = sessionTokenService;
    }

    @GetMapping
    public ApiResponse<List<AssetSummary>> listByLink(@RequestParam("linkId") long linkId) {
        return ApiResponse.success(assetService.listOwnedByLink(
                linkId,
                sessionTokenService.requireLoginId()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> read(@PathVariable long id) {
        long userId = sessionTokenService.requireLoginId();
        AssetService.AssetContent content = assetService.openOwned(id, userId);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .contentLength(content.size())
                .header("X-Content-Type-Options", "nosniff");
        if (content.format() == com.webcollector.capture.asset.entity.AssetFormat.HTML) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"snapshot.html\"");
        }
        return response.body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable long id) {
        assetService.deleteOwned(id, sessionTokenService.requireLoginId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(null, "资产已加入清理队列"));
    }
}
