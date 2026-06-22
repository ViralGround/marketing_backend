package com.viralground.backend.dto.creator;

/**
 * 인스타그램 연결 토큰 발급 응답. 프론트는 이 값으로 Phyllo Connect SDK 를 초기화한다.
 *
 * @param token       Connect SDK 토큰(단기). mock 환경에서는 mock 토큰.
 * @param userId      애그리게이터 user id(Connect SDK initialize 에 전달).
 * @param environment SDK 환경: sandbox | staging | production. mock 환경에서는 "mock".
 */
public record ConnectTokenResponse(String token, String userId, String environment) {
}
