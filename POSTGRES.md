# PostgreSQL 개발 저장소 연동

이 프로필은 CILS 계정, OAuth 클라이언트, 인가 상태와 동의를 PostgreSQL에 저장한다.
운영 배포 완료를 의미하지 않는다. PostgreSQL은 WSL의 localhost:5432, DB는 cils_db,
사용자는 cils_dev인 개발 환경에서 통합 테스트했다. 실제 버전은 서버에서 확인한다.

## 구성

- identity: cils_account에서 비밀번호 해시·enabled를 조회하여 CILS 자체 로그인.
- client: JdbcRegisteredClientRepository.
- protocol: JdbcOAuth2AuthorizationService / JdbcOAuth2AuthorizationConsentService.
- Flyway: cils 스키마에 V1 마이그레이션 적용. 기존 스키마 자동 baseline 및 clean 금지.
- keymanagement: 외부 RSA private JWK 파일 로딩. 파일이 없거나 유효하지 않으면 기동 실패.
- local은 기존 메모리 프로필이며 postgres와 동시 활성화할 수 없다.

스키마는 설치된 Spring Security 7.1.2의 SQL을 바탕으로 PostgreSQL용 text/timestamptz로
변환했다. 클라이언트 ID에는 unique index를 추가했다.
[공식 저장소 구성](https://docs.spring.io/spring-security/reference/servlet/oauth2/authorization-server/core-model-components.html)

## WSL 실행 준비

아래 명령은 Authorization 디렉터리에서 실행한다. DB 비밀번호 파일은 소유자만 접근 가능해야 한다.
비밀번호와 개인키는 저장소에 넣지 않는다.

```bash
export CILS_DB_URL='jdbc:postgresql://localhost:5432/cils_db?currentSchema=cils'
export CILS_DB_USERNAME='cils_dev'
read -r CILS_DB_PASSWORD < /root/.config/cils/db-password
export CILS_DB_PASSWORD
export CILS_SIGNING_JWK_FILE='/root/.config/cils/signing-key.json'
export CILS_ISSUER='http://localhost:9000'
```

DB 사용자는 cils 스키마 생성·테이블 생성 권한이 필요하다. DB 생성은 자동 수행하지 않는다.
초기화되지 않은 빈 스키마가 정상이며, 서버 시작 때 데모 계정은 자동 생성하지 않는다.

## 명시적 개발 데이터 준비

다음 명령은 개발용 demo 계정과 local-client를 만든다. 실제 사용자 데이터용 관리 API가 아니다.
기존 동일 계정/클라이언트가 있으면 덮어쓰지 않고 실패한다. 비밀번호는 해시로 저장한다.
개인키 파일이 없으면 한 번 생성하고 권한 600을 적용하며, 이미 있으면 교체하지 않는다.
키 저장 방식은 로컬 개발용이고 운영용 KMS/Secret Manager 결정과는 별개다.

```bash
read -rs -p 'Development account password: ' CILS_DEV_PASSWORD; echo
read -rs -p 'Development client secret: ' CILS_DEV_CLIENT_SECRET; echo
export CILS_DEV_PASSWORD CILS_DEV_CLIENT_SECRET
./gradlew preparePostgres
unset CILS_DEV_PASSWORD CILS_DEV_CLIENT_SECRET
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

콜백: http://127.0.0.1:8080/login/oauth2/code/cils
등록 grant: authorization_code, refresh_token. PKCE와 동의가 필요하다.
계정은 demo이며 입력한 개발 비밀번호로 로그인한다. 토큰 수명은 개발용 5분/1시간이다.
재시작 때 같은 DB와 같은 키 파일을 사용해야 한다.

개발 도구는 스키마 마이그레이션, 키 생성, 계정/클라이언트 fixture 생성을 수행한다.
fixture 두 건은 하나의 DB 트랜잭션으로 생성하지만 키 파일/DDL과 원자적인 작업은 아니다.
실패 시 생성 상태를 확인한다. 자동 삭제·비밀번호 재설정·키 덮어쓰기는 하지 않는다.
관리자 권한 검사·감사 원자성을 갖춘 실제 운영용 계정/앱 관리 API는 후속 구현이다.

## 검증

```bash
./gradlew test
export CILS_TEST_DB_URL='jdbc:postgresql://localhost:5432/cils_db'
./gradlew test --rerun-tasks
unset CILS_TEST_DB_URL
```

첫 명령은 PostgreSQL 환경변수가 없으면 로컬 테스트만 수행하며 DB 통합 테스트는 skip된다.
두 번째는 CILS_DB_USERNAME/CILS_DB_PASSWORD도 필요하다. 실행마다 cils_test_<UUID> 스키마를
새로 생성하며 테스트용 계정만 사용한다. 기존 cils 스키마는 테스트 데이터로 수정하지 않는다.
테스트 스키마는 검토를 위해 남긴다. 삭제는 명시적 승인 후 별도로 수행한다.

검증 범위: 올바른/틀린 비밀번호, 비활성 계정, 클라이언트와 동의 재조회,
인가 코드의 DB 저장·새 저장소 객체에서 조회, PKCE 교환, UserInfo, 갱신,
코드 재사용 거부 및 기존 local 프로필 회귀 테스트.
전체 서버 재시작 또는 다중 인스턴스 동시성 검증과는 구분한다.

## 현재 한계와 다음 단계

- HTTP 세션은 아직 메모리다. DB/키가 유지돼도 서버 재시작 후 브라우저 로그인 유지는 아직 구현되지 않았다.
- AD 조회, 앱별 역할/권한 claim, 계정·권한의 발급/갱신 시 재평가는 미구현이다.
- username이 현재 principal 및 sub다. 이름 변경은 제공하지 않는다. 안정적인 계정 ID 기반 sub 전환은 클라이언트 영향과 함께 별도 설계한다.
- JDBC 저장소 연결만으로 동시 코드 소비의 단일 성공, refresh 계열 재사용 탐지,
  세션별 grant 폐기와 로그아웃 복구가 보장되지 않는다.
- 로그아웃 엔드포인트는 기존처럼 차단한다.
- 저장한 토큰/인가 상태는 민감 데이터다. DB 접근 권한과 백업을 보호하고 로그로 덤프하지 않는다.
- 키 교체, Redis, 다중 인스턴스, 운영용 TLS·배포 구성은 후속 범위다.

## 롤백 및 변경 후 확인

이 변경은 additive 스키마 생성이다. 실패하면 서버를 멈추고 Flyway 상태와 생성된 테이블을 확인한다.
적용한 V1을 수정하거나 자동 clean/baseline으로 우회하지 않는다. 후속 변경은 새 마이그레이션으로 추가한다.
local 프로필로 돌아가도 DB의 인가 상태를 이어받지는 않는다. 데이터 삭제를 롤백으로 자동 수행하지 않는다.
배포 후 비밀값을 출력하지 않고 Flyway 버전, 로그인, 클라이언트 조회, 코드 교환과 갱신을 확인한다.