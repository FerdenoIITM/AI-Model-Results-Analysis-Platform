package com.amrap.security;

import com.amrap.domain.user.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a shared `X-Ingest-Key` header on machine-to-machine endpoints.
 *
 * <p>When the header is present and matches, the request is authenticated as a synthetic
 * "ingest" principal with role SUPERADMIN (service-level trust). When absent, the filter is a
 * no-op so the regular JWT filter can take over.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Ingest-Key";
    /** Not a row in users. Machine ingest must store created_by as null. */
    public static final UUID SERVICE_PRINCIPAL_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000A1E7");

    private final String expectedKey;

    public ApiKeyAuthenticationFilter(
            @Value("${amrap.rag.ingest-api-key:change-me-ingest-key}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key != null && !key.isBlank() && key.equals(expectedKey)) {
            AuthenticatedUser principal = new AuthenticatedUser(
                    SERVICE_PRINCIPAL_ID,
                    "ingest@amrap.local",
                    "ingest-service",
                    UserRole.SUPERADMIN,
                    "",
                    true);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }
}
