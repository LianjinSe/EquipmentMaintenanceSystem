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

/** Keep this unauthenticated demonstration local even if the shared Tomcat binds to all interfaces. */
public final class LocalOnlyFilter implements Filter {
    static boolean isLoopback(String address) {
        if (address == null || !address.matches("[0-9A-Fa-f:.]+")) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException error) {
            return false;
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse output)) {
            throw new ServletException("HTTP request required");
        }
        if (!isLoopback(http.getRemoteAddr())) {
            output.sendError(HttpServletResponse.SC_FORBIDDEN, "This demo is available only on this computer.");
            return;
        }
        chain.doFilter(request, response);
    }
}
