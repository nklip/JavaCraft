package dev.nklip.javacraft.ewrs.dashboard.controller;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.ModelAndView;

class DashboardPageControllerTest {

    @Test
    void indexReturnsDashboardTemplate() {
        DashboardPageController controller = new DashboardPageController();

        ModelAndView modelAndView = controller.index();

        Assertions.assertAll(
                () -> Assertions.assertEquals("dashboard/index", modelAndView.getViewName()),
                () -> Assertions.assertEquals("EWRS Dashboard", modelAndView.getModel().get("pageTitle")),
                () -> Assertions.assertEquals("/api/v1/dashboard", modelAndView.getModel().get("dashboardApiBasePath")),
                () -> Assertions.assertInstanceOf(String.class, modelAndView.getModel().get("assetVersion")),
                () -> Assertions.assertFalse(((String) modelAndView.getModel().get("assetVersion")).isBlank())
        );
    }
}
