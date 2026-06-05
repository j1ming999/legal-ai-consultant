package org.example.consultant3.config;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.consultant3.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    private static final String[] PUBLIC_PATHS = {
            "/api/user/register",
            "/api/user/login"
    };

    private static final String[] OPTIONAL_AUTH_PATHS = {
            "/chat",
            "/chat/sources",
            "/test/"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        for (String pub : PUBLIC_PATHS) {
            if (path.equals(pub)) return true;
        }

        if (path.equals("/") || path.endsWith(".html") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".ico") || path.endsWith(".png")) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        boolean isOptional = false;
        for (String opt : OPTIONAL_AUTH_PATHS) {
            if (path.startsWith(opt)) {
                isOptional = true;
                break;
            }
        }

        if (token != null) {
            try {
                DecodedJWT jwt = jwtUtil.verifyToken(token);
                request.setAttribute("userId", jwt.getClaim("userId").asLong());
                request.setAttribute("username", jwt.getClaim("username").asString());
                return true;
            } catch (Exception e) {
                if (isOptional) return true;
                response.setStatus(401);
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"error\":\"token无效或已过期\"}");
                return false;
            }
        }

        if (isOptional) return true;

        if (path.startsWith("/api/")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"error\":\"请先登录\"}");
            return false;
        }

        return true;
    }
}
