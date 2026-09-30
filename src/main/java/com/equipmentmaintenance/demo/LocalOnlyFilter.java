package com.equipmentmaintenance.demo;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keep this unauthenticated demonstration local even if the shared Tomcat binds to all interfaces. */
public final class LocalOnlyFilter implements Filter {
    private static final Pattern LOCAL_HOST = Pattern.compile(
            "^(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::([0-9]{1,5}))?$", Pattern.CASE_INSENSITIVE);

    static boolean isLoopback(String address) {
        if (address == null || !address.matches("[0-9A-Fa-f:.]+")) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    static boolean isLocalHostHeader(String hostHeader) {
        if (hostHeader == null) return false;
        Matcher match = LOCAL_HOST.matcher(hostHeader);
        if (!match.matches()) return false;
        String port = match.group(1);
        if (port == null) return true;
        int value = Integer.parseInt(port);
        return value >= 1 && value <= 65535;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse output)) {
            throw new ServletException("HTTP request required");
        }
        if (!isLoopback(http.getRemoteAddr()) || !isLocalHostHeader(http.getHeader("Host"))) {
            output.sendError(HttpServletResponse.SC_FORBIDDEN, "This demo is available only on this computer.");
            return;
        }
        output.setHeader("Cache-Control", "no-store");
        output.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
