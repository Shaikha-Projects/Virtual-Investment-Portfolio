package com.ga.investmentservice.Security;

import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtParser parser;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(String secret, JdbcTemplate jdbc) {
        //authentication uses the String overload, which decodes the shared Base64 secret.
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

                //read current identity, status and role without copying Authentication's User entity.
                List<AuthenticatedUser> users = jdbc.query(
                        "select id, email_address, role from users where email_address = ? and status = 'ACTIVE'",
                        (row, rowNumber) -> new AuthenticatedUser(row.getLong("id"),
                                row.getString("email_address"), row.getString("role")), email);
                if (users.size() != 1) throw new IllegalArgumentException();
                AuthenticatedUser user = users.get(0);
                if (user.getUserId() == null || user.getUserId() <= 0
                        || !("USER".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
                    throw new IllegalArgumentException();
                }
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))));
                SecurityContextHolder.setContext(context);
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            } catch (DataAccessException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
