package dev.nklip.javacraft.ewrs.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Renders the Thymeleaf shell for the EWRS dashboard UI.
 * Architecture mapping: the browser entry point for the read-only visualization layer that then hydrates itself from
 * the dashboard JSON endpoints.
 */
@Controller
@RequestMapping("/")
public class DashboardPageController {

    private final String assetVersion = Long.toHexString(System.currentTimeMillis());

    @GetMapping
    public ModelAndView index() {
        return new ModelAndView("dashboard/index")
                .addObject("pageTitle", "EWRS Dashboard")
                .addObject("dashboardApiBasePath", "/api/v1/dashboard")
                .addObject("assetVersion", assetVersion);
    }
}
