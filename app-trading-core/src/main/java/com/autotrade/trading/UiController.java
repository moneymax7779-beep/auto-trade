package com.autotrade.trading;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Client-side routes of the single-page UI all load index.html. */
@Controller
class UiController {

    @GetMapping({"/", "/live", "/index/{underlying}", "/strategies", "/sessions", "/sessions/{id}", "/research", "/research/{id}",
        "/replay", "/config"})
    String index() {
        return "forward:/index.html";
    }
}
