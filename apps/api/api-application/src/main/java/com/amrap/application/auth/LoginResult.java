package com.amrap.application.auth;

public record LoginResult(String accessToken, UserSummary user) {
}
