package com.amrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amrap.domain.user.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthenticationFiltersTest {

    private final JwtService jwtService = new JwtService(JwtServiceTest.SECRET, 60_000);
    private final JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    private final ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter("secret-ingest-key");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ingestFilterRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyAuthenticationFilter("  ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validBearerTokenAuthenticatesWithRoleAuthority() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtService.generateToken(id, "a@x.io", UserRole.ADMIN, "Alice"));
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();
        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void invalidTokenLeavesRequestUnauthenticatedButContinues() throws Exception {
        for (String header : new String[] {"Bearer garbage", "Bearer ", "Basic abc", "bearer " + token()}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Authorization", header);
            MockFilterChain chain = new MockFilterChain();

            jwtFilter.doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(header).isNull();
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    @Test
    void missingHeaderIsNoOp() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        apiKeyFilter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void correctIngestKeyAuthenticatesAsServiceSuperadmin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, "secret-ingest-key");

        apiKeyFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        AuthenticatedUser user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(user.getId()).isEqualTo(ApiKeyAuthenticationFilter.SERVICE_PRINCIPAL_ID);
        assertThat(user.getRole()).isEqualTo(UserRole.SUPERADMIN);
    }

    @Test
    void wrongBlankOrDifferentCaseKeyIsIgnored() throws Exception {
        for (String key : new String[] {"wrong", " ", "SECRET-INGEST-KEY", "secret-ingest-key "}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ApiKeyAuthenticationFilter.HEADER, key);

            apiKeyFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(key).isNull();
        }
    }

    @Test
    void securityUtilsRequiresPrincipal() {
        org.assertj.core.api.Assertions.assertThatThrownBy(SecurityUtils::requireCurrentUser)
                .isInstanceOf(IllegalStateException.class);
    }

    private String token() {
        return jwtService.generateToken(UUID.randomUUID(), "a@x.io", UserRole.MEMBER, "M");
    }
}
