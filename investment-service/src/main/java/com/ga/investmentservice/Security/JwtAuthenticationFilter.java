package com.ga.investmentservice.Security;

import io.jsonwebtoken.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ga.investmentservice.Client.AuthServiceClient;
import com.ga.investmentservice.DTO.Response.UserIdentityResponse;
import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtParser parser;
    private final AuthServiceClient authServiceClient;

    public JwtAuthenticationFilter(String secret, AuthServiceClient authServiceClient) {
        this.parser = Jwts.parserBuilder().setSigningKey(secret).build();
        this.authServiceClient = authServiceClient;
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

                //get current user identity from Authentication using REST Client
                UserIdentityResponse identity = authServiceClient.getUserByEmail(email);

                if (!email.equals(identity.emailAddress())
                        || !"ACTIVE".equals(identity.status())
                        || !("USER".equals(identity.role()) || "ADMIN".equals(identity.role()))) {
                    throw new IllegalArgumentException();
                }

                AuthenticatedUser user = new AuthenticatedUser(
                        identity.userId(),
                        identity.emailAddress(),
                        identity.role()
                );

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
