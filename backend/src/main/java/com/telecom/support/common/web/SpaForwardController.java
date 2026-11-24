package com.telecom.support.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React SPA for client-side routes. Any GET path without a file extension that is not
 * under /api or /actuator is forwarded to index.html so that deep links (e.g. /customer/requests) work.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/",
        "/{path:^(?!api$|actuator$)[^\\.]*}",
        "/{path:^(?!api$|actuator$)[^\\.]*}/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
