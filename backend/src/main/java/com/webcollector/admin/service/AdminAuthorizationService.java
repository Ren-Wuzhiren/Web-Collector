package com.webcollector.admin.service;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.entity.UserRole;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthorizationService {

    private final SessionTokenService sessionTokenService;
    private final AppUserMapper appUserMapper;

    public AdminAuthorizationService(
            SessionTokenService sessionTokenService,
            AppUserMapper appUserMapper
    ) {
        this.sessionTokenService = sessionTokenService;
        this.appUserMapper = appUserMapper;
    }

    // [业务] 角色每次从数据库读取，管理员提升或撤销无须重建 Sa-Token 会话。
    @Transactional(readOnly = true)
    public AppUser requireAdmin() {
        Long userId = sessionTokenService.requireLoginId();
        AppUser user = appUserMapper.selectById(userId);

        if (user == null) {
            sessionTokenService.logout();
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        if (user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return user;
    }
}
