package com.webcollector.admin.service;

import com.webcollector.auth.entity.AppUser;
import com.webcollector.auth.entity.UserRole;
import com.webcollector.auth.mapper.AppUserMapper;
import com.webcollector.auth.session.SessionTokenService;
import com.webcollector.common.web.BusinessException;
import com.webcollector.common.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthorizationServiceTest {

    private SessionTokenService sessionTokenService;
    private AppUserMapper appUserMapper;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        sessionTokenService = mock(SessionTokenService.class);
        appUserMapper = mock(AppUserMapper.class);
        service = new AdminAuthorizationService(
                sessionTokenService,
                appUserMapper
        );
    }

    // [测试意图] 验证数据库中的 ADMIN 角色可以通过统一管理门禁。
    @Test
    void shouldAllowAdminUser() {
        AppUser admin = user(UserRole.ADMIN);
        when(sessionTokenService.requireLoginId()).thenReturn(1L);
        when(appUserMapper.selectById(1L)).thenReturn(admin);

        assertThat(service.requireAdmin()).isSameAs(admin);
    }

    // [测试意图] 验证普通用户被拒绝，且不会注销仍然有效的会话。
    @Test
    void shouldRejectRegularUserWithoutLoggingOut() {
        when(sessionTokenService.requireLoginId()).thenReturn(1L);
        when(appUserMapper.selectById(1L)).thenReturn(user(UserRole.USER));

        assertThatThrownBy(service::requireAdmin)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN)
                );

        verify(sessionTokenService, never()).logout();
    }

    // [测试意图] 验证已删除用户的有效 Token 会被清理并返回未认证。
    @Test
    void shouldLogoutWhenSessionUserNoLongerExists() {
        when(sessionTokenService.requireLoginId()).thenReturn(1L);
        when(appUserMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(service::requireAdmin)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTH_REQUIRED)
                );

        verify(sessionTokenService).logout();
    }

    private static AppUser user(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("student");
        user.setRole(role);
        return user;
    }
}
