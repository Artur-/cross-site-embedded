package org.vaadin.artur.cors;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebFilter(filterName = "Cors Filter", asyncSupported = true, value = "/*")
public class CorsFilter implements Filter {
    private static final String SET_COOKIE_HEADER = "Set-Cookie";
    private Set<String> allowedOrigins = new HashSet<>();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        readConfig();
        getLogger().info("Initialized " + getClass().getName());
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String origin = request.getHeader("Origin");
        debug("Request for " + request.getRequestURI());
        if (needsCorsHeaders(request) && isAllowedRequestOrigin(origin)) {
            getLogger().debug("CORS request from {} for {}", origin, request.getPathInfo());
            response.addHeader("Access-Control-Allow-Origin", origin);
            response.addHeader("Access-Control-Allow-Credentials", "true");

            // Handle a preflight "option" requests
            if ("options".equalsIgnoreCase(request.getMethod())) {
                response.addHeader("Access-Control-Allow-Methods", "GET, POST");
                response.addHeader("Access-Control-Allow-Headers", "content-type");

                response.setContentType("text/plain");
                response.setCharacterEncoding("utf-8");
                response.getWriter().flush();
                return;
            } else {
                response.addHeader("Vary", "Origin");
            }
        }

        UndertowCookieSupport.handleSessionCookie(response);
        chain.doFilter(request, response);
        rewriteSessionCookieForCrossSite(response);

    }

    private void rewriteSessionCookieForCrossSite(HttpServletResponse response) {
        Collection<String> cookieHeaders = response.getHeaders(SET_COOKIE_HEADER);

        // Find "JSESSIONID=abc" without ";SameSite=xyz"
        Optional<String> sessionIdCookie = cookieHeaders.stream().filter(cookie -> cookie.startsWith("JSESSIONID"))
                .filter(cookie -> !cookie.toLowerCase().contains("samesite")).findAny();
        if (!sessionIdCookie.isPresent()) {
            debug("No session cookie found");
            debug("headers: " + response.getHeaderNames());
            return;
        }

        String newSessionCookie = makeSameSite(sessionIdCookie.get());
        response.setHeader(SET_COOKIE_HEADER, newSessionCookie);
        debug("Changing JSESSIONID to " + newSessionCookie);
        if (response.isCommitted()) {
            debug("response is committed");
            return;
        }
        cookieHeaders.stream().filter(cookie -> !cookie.startsWith("JSESSIONID")).forEach(cookie -> {
            response.addHeader(SET_COOKIE_HEADER, cookie);
        });
    }

    private String makeSameSite(String string) {
        if (string.contains(("SameSite=")))
            return string;

        return string + ";SameSite=None";
    }

    static void debug(String string) {
        getLogger().debug(string);
    }

    @Override
    public void destroy() {
    }

    private void readConfig() {
        Properties props = new Properties();
        try {
            InputStream stream = getClass().getResourceAsStream("/cors.properties");
            if (stream != null) {
                props.load(stream);
            }
            String origins = props.getProperty("origins");
            if (origins != null && !origins.isEmpty()) {
                Stream.of(origins.split(",")).map(origin -> origin.trim()).forEach(allowedOrigins::add);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (allowedOrigins.isEmpty()) {
            getLogger().warn("No CORS origins defined. Allowing all origins for TESTING PURPOSES.");
            getLogger().warn(
                    "Define allowed origins in src/main/resources/cors.properties as origins=origin1,origin2 before deploying");
            allowedOrigins.add("*");
        }

        getLogger().info("Allowing embedding from: " + allowedOrigins.stream().collect(Collectors.joining(", ")));
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(CorsFilter.class);
    }

    private boolean needsCorsHeaders(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        String servletPath = request.getServletPath();
        String path = "";
        if (servletPath != null) {
            path += servletPath;
        }
        if (pathInfo != null) {
            path += pathInfo;
        }

        if ("uidl".equals(request.getParameter("v-r"))) {
            // Vaadin UIDL request
            return true;
        } else if ("heartbeat".equals(request.getParameter("v-r"))) {
            // Heartbeats need to go through or the session expires
            return true;
        }

        return path.startsWith("/VAADIN/build/") || path.startsWith("/web-component/");
    }

    private boolean isAllowedRequestOrigin(String origin) {
        debug("Checking if origin is ok: " + origin);
        if (origin == null)
            return false;
        if (allowedOrigins.contains("*")) {
            return true;
        }
        return allowedOrigins.contains(origin);
    }

}