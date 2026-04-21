package com.viralground.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/debug")
public class DebugController {

    @GetMapping("/sentry")
    public void triggerError() {
        throw new RuntimeException("Sentry 연동 테스트");
    }
}
