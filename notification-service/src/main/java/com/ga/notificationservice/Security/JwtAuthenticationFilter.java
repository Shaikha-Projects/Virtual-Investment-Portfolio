package com.ga.notificationservice.Security;

import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtParser parser;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(String secret, JdbcTemplate jdbc) {
        // Authentication uses the JJWT String overload: the shared secret is Base64 encoded.
        this.parser = Jwts.parserBuilder().setSigningKey(secret).build();
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                if (!header.startsWith("Bearer ")) throw new IllegalArgumentException();
                Jws<Claims> jwt = parser.parseClaimsJws(header.substring(7));
                Claims claims = jwt.getBody();
                String email = claims.getSubject();
                if (!"HS256".equals(jwt.getHeader().getAlgorithm()) || claims.getExpiration() == null
                        || email == null || email.isBlank()) throw new IllegalArgumentException();
                // Read current status on every connection; no passwords or copied user model needed.
                List<String> users = jdbc.queryForList(
                        "select email_address from users where email_address = ? and status = 'ACTIVE'",
                        String.class, email);
                if (users.size() != 1) throw new IllegalArgumentException();
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(users.get(0), null, List.of()));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
