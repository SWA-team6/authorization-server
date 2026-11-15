# 로컬 OAuth 2.0 / OIDC 인증 서버

기존 Java 21, Spring Boot 4.1.2, Gradle Wrapper를 유지한 기본 구현입니다.
Lombok을 사용하지 않습니다. 진입점은 `com.team6.cils.CilsApplication`입니다.

## 실행

Java 21 환경에서 이 디렉터리에서 실행합니다.

```bash
export CILS_LOCAL_PASSWORD='choose-a-local-test-password'
# {bcrypt} 접두사를 포함한 PasswordEncoder 형식의 해시를 권장합니다.
# 아래 {noop} 값은 로컬 실험에만 사용합니다.
export CILS_LOCAL_CLIENT_SECRET_HASH='{noop}choose-a-local-client-secret'
./gradlew bootRun --args='--spring.profiles.active=local'
```

로그인 계정은 `demo`, 비밀번호는 위 환경변수 값입니다.
`CILS_LOCAL_USERNAME`으로 계정 이름을 변경할 수 있습니다.
서버는 루프백 주소의 9000 포트에만 바인딩됩니다.
issuer는 `http://localhost:9000`이며 요청에도 같은 호스트를 사용합니다.
프로필 없이 실행하면 실패합니다. 현재 운영용 저장소/키 어댑터는 없습니다.

## 클라이언트 연결

- Client ID: `local-client`
- Client authentication: `client_secret_basic`; 실제 비밀은 위 해시의 원문
- Redirect URI: `http://127.0.0.1:8080/login/oauth2/code/cils`
- Grant types: `authorization_code`, `refresh_token`
- Scopes: `openid profile`
- PKCE 필수, S256 사용; 클라이언트가 state, nonce와 verifier를 생성·보관·검증
- 로그인/동의 화면은 Spring Security 기본 화면 사용

8080의 콜백은 연결할 애플리케이션이 구현해야 합니다. 이 프로젝트에는 클라이언트 앱이 없습니다.
등록 정보는 `application-local.properties`의 Spring Boot 클라이언트 설정에 있습니다.
추가 클라이언트도 같은 설정 구조로 등록할 수 있지만, 런타임 관리 API는 아직 없습니다.

Discovery: <http://localhost:9000/.well-known/openid-configuration>

프레임워크가 제공하는 경로:
`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`,
`/userinfo`, `/oauth2/revoke`, `/oauth2/introspect`.
토큰 요청은 form encoding과 등록된 클라이언트 인증을 사용합니다.
사용자 비밀번호를 token endpoint로 보내는 password grant는 지원하지 않습니다.

## 설계와 한계

프로토콜 처리를 직접 구현하지 않고 Spring Security의 Authorization Server 설정을 사용합니다.
별도 필터 체인으로 프로토콜과 브라우저 로그인 보안을 구성하며 로그인 CSRF 보호를 유지합니다.
계정은 identity, 키 생성은 keymanagement, grant/consent 저장소는 protocol,
필터 조합은 security에 위치합니다. 클라이언트 저장소는 Boot의 설정 기반 자동 구성을 사용합니다.

이 단계는 단일 프로세스 개발용입니다. 계정·클라이언트·동의·grant·세션은 메모리에 있으며,
재시작하면 상태와 서명 키가 초기화됩니다. 운영 저장소나 키 관리 방식의 결정이 아닙니다.
개발 설정의 access token 수명은 5분, refresh token은 1시간, 세션은 30분입니다.
refresh token rotation은 활성화했지만 CILS 목표의 영속적 재사용 탐지/가족 전체 폐기,
동시성 보장, 계정·권한 재평가는 구현하지 않았습니다.
profile scope가 있더라도 별도 이름/이메일 데이터는 제공하지 않습니다.

전체 SSO 로그아웃은 durable 작업 저장 및 grant 무효화가 필요하므로
`/connect/logout`, `/logout`은 404로 차단하고 discovery에서 logout endpoint를 제외합니다.
권한 관리, 관리 API, 감사 기록, API별 audience 정책, 영속 상태, Docker 운영 배포는 후속 범위입니다.
승인된 전체 요구사항은 [아키텍처](../docs/architecture.md)에 유지됩니다.

## 검증

```bash
./gradlew test
```

MockMvc 테스트는 discovery, 공개 JWK, 로그인, CSRF, UserInfo 인증 요구,
Authorization Code + PKCE 교환, UserInfo, refresh rotation, 코드 재사용 거부 및 로그아웃 차단을 검사합니다.
실제 두 애플리케이션 간 SSO나 운영 저장소 동시성을 검증하는 테스트는 아닙니다.

설정 근거: [Spring Security Authorization Server 시작 가이드](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/getting-started.html).
기본 구성으로 빠르게 프로토콜을 연결할 수 있지만 영속성과 CILS 업무 정책은 별도 구현이 필요합니다.
