package com.commercesettle.democontrol;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** loopback Host와 데모 UI 출처만 허용한다. 임의 사이트가 로컬 운영 기능을 호출하지 않게 한다. */
@Component
class LocalRequestFilter extends OncePerRequestFilter {
  private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    boolean valid =
        HOSTS.contains(request.getServerName())
            && !"cross-site".equals(request.getHeader("Sec-Fetch-Site"));
    if (origin != null) {
      try {
        URI uri = URI.create(origin);
        valid &=
            "http".equals(uri.getScheme())
                && HOSTS.contains(uri.getHost())
                && (uri.getPort() == request.getServerPort() || uri.getPort() == 5173)
                && uri.getUserInfo() == null;
      } catch (IllegalArgumentException e) {
        valid = false;
      }
    }
    if (request.getMethod().equals("POST")) {
      valid &=
          request.getContentType() != null
              && request
                  .getContentType()
                  .toLowerCase(java.util.Locale.ROOT)
                  .startsWith("application/json");
    }
    if (!valid) {
      response.setStatus(403);
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"status\":403,\"code\":\"DEMO_ORIGIN_REJECTED\",\"detail\":\"로컬 UI 출처만 허용합니다.\"}");
      return;
    }
    chain.doFilter(request, response);
  }
}
