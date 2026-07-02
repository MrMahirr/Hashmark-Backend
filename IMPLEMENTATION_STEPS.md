# Hashmark Backend - Adim Adim Uygulama Rehberi

> **Kural:** Her adim tamamlanmadan bir sonrakine gecilmez.
> **Kural:** Her adim sonunda kisa ozet verilir: ne yapildi, ne test edildi, sonuc neydi.

---

## ADIM 1 - Proje Iskeleti ve Bagimliliklar

### 1.1 - `pom.xml` Guncelleme

- [x] Asagidaki dependency'leri ekle:
  - `springdoc-openapi-starter-webmvc-ui` (2.6.x)
  - `spring-boot-starter-actuator`
  - `spring-boot-starter-aop`
  - `io.jsonwebtoken:jjwt-api:0.12.6`
  - `io.jsonwebtoken:jjwt-impl:0.12.6` (runtime scope)
  - `io.jsonwebtoken:jjwt-jackson:0.12.6` (runtime scope)
- [x] Asagidaki dependency'leri kaldir:
  - `spring-boot-starter-session-data-redis`
  - `spring-boot-starter-session-data-redis-test`
  - `spring-boot-starter-flyway` (zaten `flyway-database-postgresql` var, `flyway-core` yeterli)
  - `spring-boot-starter-flyway-test`
- [x] `spring-boot-starter-webmvc` -> `spring-boot-starter-web` olarak degistir
- [x] `spring-boot-starter-webmvc-test` -> `spring-boot-starter-test` olarak degistir

### 1.2 - `application.yml` Yapilandirma

- [x] `src/main/resources/application.yml` dosyasini doldur:
  ```yaml
  server:
    port: ${SERVER_PORT:8080}

  spring:
    datasource:
      url: ${DATABASE_URL}
      username: ${DATABASE_USERNAME}
      password: ${DATABASE_PASSWORD}
      driver-class-name: org.postgresql.Driver
    flyway:
      enabled: true
      locations: classpath:db/migration

  springdoc:
    swagger-ui:
      path: /swagger-ui.html
    api-docs:
      path: /v1/api-docs

  github:
    client-id: ${GITHUB_CLIENT_ID}
    client-secret: ${GITHUB_CLIENT_SECRET}
    callback-url: http://localhost:8080/auth/callback

  jwt:
    secret: ${JWT_SECRET}
    access-token-expiry: 900000
    refresh-token-expiry: 604800000

  encryption:
    secret: ${ENCRYPTION_SECRET}

  resend:
    api-key: ${RESEND_API_KEY:placeholder}
    from-email: ${RESEND_FROM_EMAIL:noreply@hashmark.dev}

  async:
    core-pool-size: 2
    max-pool-size: 5
    queue-capacity: 50
  ```
- [x] `application.properties` dosyasini sil (cakisma onlenir)

### 1.3 - Application Sinifi Duzeltme

- [x] `src/main/java/dev/hashmark/HashmarkApplication.java` dosyasini doldur:
  ```java
  @SpringBootApplication
  @EnableScheduling
  public class HashmarkApplication {
      public static void main(String[] args) {
          SpringApplication.run(HashmarkApplication.class, args);
      }
  }
  ```
- [x] `src/main/java/dev/hashmark/hashmarkbackend/` klasorunu tamamen sil (yanlis paket)

### 1.4 - Environment Variables

- [x] `.env` dosyasina ekle:
  ```
  ENCRYPTION_SECRET=0123456789abcdef0123456789abcdef
  RESEND_API_KEY=your_resend_api_key
  RESEND_FROM_EMAIL=noreply@hashmark.dev
  ```
- [x] `.env.example` dosyasini guncelle (degerler bos)

### 1.5 - Docker Compose Kurulumu

- [x] `docker-compose.yml` olustur:
  - PostgreSQL 16 container (`hashmark-postgres`)
  - 5432 port mapping
  - Volume tanimi (`postgres_data`)
- [x] `docker-compose up -d` ile veritabanini baslat

### ✅ ADIM 1 Dogrulama

- [x] `mvn compile` hatasiz tamamlanir
- [x] Gereksiz `hashmarkbackend` paketi silinmis
- [x] `application.yml` tum konfigirasyonu icerir
- [x] `application.properties` silinmis

---

## ADIM 2 - Common Altyapi

### 2.1 - JWT Utility

- [x] `common/util/JwtUtil.java` olustur:
  - `@Component`
  - `@Value("${jwt.secret}")` ile secret inject
  - `@Value("${jwt.access-token-expiry}")` ile access expiry inject
  - `@Value("${jwt.refresh-token-expiry}")` ile refresh expiry inject
  - `generateAccessToken(Long userId)` -> HMAC-SHA256, 15 dk gecerli
  - `generateRefreshToken(Long userId)` -> HMAC-SHA256, 7 gun gecerli
  - `validateToken(String token)` -> boolean (try-catch ile parse)
  - `extractUserId(String token)` -> Long (subject claim'den)

### 2.2 - AES Encryption Utility

- [x] `common/util/AesEncryptionUtil.java` olustur:
  - `@Component`
  - `@Value("${encryption.secret}")` ile key inject
  - `encrypt(String plainText)` -> AES-256-GCM, 12-byte IV, IV+ciphertext birlestir, Base64 encode
  - `decrypt(String cipherText)` -> Base64 decode, ilk 12 byte IV, geri kalan ciphertext, coz
  - `@PostConstruct` ile key'i `SecretKeySpec` olarak hazirla

### 2.3 - Exception Handling

- [x] `common/exception/ApiException.java` olustur:
  - `extends RuntimeException`
  - Alanlar: `HttpStatus httpStatus`, `String message`
  - Constructor: `ApiException(HttpStatus status, String message)`
  - Factory metodlar:
    - `static notFound(String message)`
    - `static badRequest(String message)`
    - `static unauthorized(String message)`
    - `static forbidden(String message)`

- [x] `common/exception/ErrorResponse.java` olustur:
  - Alanlar: `LocalDateTime timestamp`, `int status`, `String error`, `String message`
  - `@Builder` ile Lombok

- [x] `common/exception/GlobalExceptionHandler.java` olustur:
  - `@RestControllerAdvice`
  - `@ExceptionHandler(ApiException.class)` -> ErrorResponse dondur
  - `@ExceptionHandler(MethodArgumentNotValidException.class)` -> 400 dondur
  - `@ExceptionHandler(Exception.class)` -> 500 dondur (fallback)

### 2.4 - Async Konfigurasyonu

- [x] `common/config/AsyncConfig.java` olustur:
  - `@Configuration`, `@EnableAsync`
  - `@Value` ile pool ayarlari inject
  - `@Bean("taskExecutor")` -> `ThreadPoolTaskExecutor` dondur
    - corePoolSize, maxPoolSize, queueCapacity set et
    - threadNamePrefix: `hashmark-async-`

### 2.5 - Swagger Konfigurasyonu

- [x] `common/config/SwaggerConfig.java` olustur:
  - `@Configuration`
  - `@Bean OpenAPI openAPI()`:
    - Info: title="Hashmark API", version="v1", description
    - SecurityScheme: "bearerAuth", type=HTTP, scheme=bearer, bearerFormat=JWT
    - SecurityRequirement: "bearerAuth"

### 2.6 - CORS Konfigurasyonu

- [x] `common/config/CorsConfig.java` olustur:
  - `@Configuration` implements `WebMvcConfigurer`
  - `addCorsMappings(CorsRegistry registry)`:
    - `/**` pattern
    - allowedOrigins: `http://localhost:3000`
    - allowedMethods: GET, POST, PUT, DELETE, OPTIONS
    - allowedHeaders: `*`
    - allowCredentials: true

### 2.7 - Security Konfigurasyonu

- [x] `common/config/JwtAuthenticationFilter.java` olustur:
  - `extends OncePerRequestFilter`
  - `JwtUtil` inject
  - `doFilterInternal()`:
    1. Authorization header'dan "Bearer " prefix'ini cikar
    2. Token yoksa -> chain devam
    3. `jwtUtil.validateToken()` -> false ise chain devam
    4. `jwtUtil.extractUserId()` -> `UsernamePasswordAuthenticationToken` olustur
    5. `SecurityContextHolder`'a set et
    6. `filterChain.doFilter()` cagir

- [x] `common/config/SecurityConfig.java` olustur:
  - `@Configuration`, `@EnableWebSecurity`
  - `JwtAuthenticationFilter` inject
  - `@Bean SecurityFilterChain filterChain(HttpSecurity http)`:
    - CSRF disabled
    - Session management: STATELESS
    - Permit all: `/auth/**`, `/swagger-ui/**`, `/v1/api-docs/**`, `/actuator/health`
    - Diger tumu: authenticated
    - `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)`
    - CORS enabled

### ✅ ADIM 2 Dogrulama

- [x] `mvn compile` hatasiz tamamlanir
- [x] `JwtUtilTest.java` yazilir ve gecer:
  - Token uretilir -> null degil
  - Token dogrulanir -> true doner
  - userId extract edilir -> dogru deger
  - Suresi gecmis token -> false doner
- [x] `AesEncryptionUtilTest.java` yazilir ve gecer:
  - Encrypt -> decrypt -> orijinal metin eslesir
  - Farkli plaintext -> farkli ciphertext
- [x] `mvn test` gecer

---

## ADIM 3 - Flyway Migration Dosyalari

### 3.1 - V1: Temel Tablolar

- [x] `V1__init_schema.sql` dosyasini doldur:

... (unchanged)

### 3.2 - V2: Scan Jobs

- [x] `V2__add_scan_jobs.sql` dosyasini doldur:

... (unchanged)

### 3.3 - V3: User Settings

- [x] `V3__add_user_settings.sql` dosyasini doldur:

... (unchanged)

### ✅ ADIM 3 Dogrulama

- [x] `mvn spring-boot:run` ile uygulama ayaga kalkar (PostgreSQL baglantisi gerekir)
- [x] `SELECT * FROM flyway_schema_history;` -> 3 satir, hepsi `success = true`
- [x] `\dt` ile 5 tablo listelenir: users, repos, debts, scan_jobs, user_settings
- [x] `http://localhost:8080/swagger-ui.html` acilir (henuz endpoint yok)
- [x] `GET /actuator/health` -> `{ "status": "UP" }`

---

## ADIM 4 - Auth Modulu

### 4.1 - Model ve DTO'lar

- [x] `auth/model/User.java` olustur:
  - Alanlar: `Long id`, `String githubId`, `String email`, `String name`, `String githubToken`, `LocalDateTime createdAt`
  - Lombok: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`

- [x] `auth/dto/GitHubUserDto.java` olustur:
  - Alanlar: `String id`, `String login`, `String email`, `String name`, `String avatarUrl`
  - `@JsonProperty("avatar_url")` mapping

- [x] `auth/dto/LoginResponse.java` olustur:
  - Alanlar: `String accessToken`, `String refreshToken`, `Long expiresIn`

- [x] `auth/dto/RefreshRequest.java` olustur:
  - Alanlar: `String refreshToken`

### 4.2 - Repository

- [x] `auth/repository/UserRepository.java` olustur:
  - `@Repository`, `JdbcTemplate` inject (`@RequiredArgsConstructor`)
  - `RowMapper<User>` tanimla (private static final veya lambda)
  - `Optional<User> findByGithubId(String githubId)`:
    ```sql
    SELECT * FROM users WHERE github_id = ?
    ```
  - `User save(User user)` - UPSERT:
    ```sql
    INSERT INTO users (github_id, email, name, github_token)
    VALUES (?, ?, ?, ?)
    ON CONFLICT (github_id)
    DO UPDATE SET email = EXCLUDED.email, name = EXCLUDED.name, github_token = EXCLUDED.github_token
    RETURNING id, github_id, email, name, github_token, created_at
    ```
  - `Optional<User> findById(Long id)`:
    ```sql
    SELECT * FROM users WHERE id = ?
    ```

### 4.3 - Service'ler

- [x] `auth/service/GitHubOAuthService.java` olustur:
  - `@Service`
  - `@Value("${github.client-id}")`, `@Value("${github.client-secret}")`, `@Value("${github.callback-url}")`
  - `RestTemplate` inject
  - `String buildAuthorizationUrl()`:
    - URL: `https://github.com/login/oauth/authorize`
    - Params: `client_id`, `scope=repo,user:email`, `state=<random UUID>`
  - `String exchangeCodeForToken(String code)`:
    - POST `https://github.com/login/oauth/access_token`
    - Body: `client_id`, `client_secret`, `code`
    - Header: `Accept: application/json`
    - Response'tan `access_token` extract
  - `GitHubUserDto fetchUserInfo(String accessToken)`:
    - GET `https://api.github.com/user`
    - Header: `Authorization: Bearer {token}`

- [x] `auth/service/AuthService.java` olustur:
  - `@Service`
  - Inject: `GitHubOAuthService`, `UserRepository`, `JwtUtil`, `AesEncryptionUtil`
  - `private final Set<String> tokenBlacklist = ConcurrentHashMap.newKeySet()`
  - `String initiateLogin()` -> `gitHubOAuthService.buildAuthorizationUrl()`
  - `LoginResponse handleCallback(String code)`:
    1. `exchangeCodeForToken(code)` -> githubToken
    2. `fetchUserInfo(githubToken)` -> userDto
    3. GitHub token'i `aesEncryptionUtil.encrypt()` ile sifrele
    4. `userRepository.save()` -> user (upsert)
    5. `jwtUtil.generateAccessToken(user.getId())` -> accessToken
    6. `jwtUtil.generateRefreshToken(user.getId())` -> refreshToken
    7. `LoginResponse` dondur
  - `String refresh(String refreshToken)`:
    1. Blacklist kontrolu
    2. `jwtUtil.validateToken()` -> false ise ApiException.unauthorized()
    3. `jwtUtil.extractUserId()` -> userId
    4. `jwtUtil.generateAccessToken(userId)` -> yeni accessToken
  - `void logout(String refreshToken)`:
    1. `tokenBlacklist.add(refreshToken)`

### 4.4 - Controller

- [x] `auth/controller/AuthController.java` olustur:
  - `@RestController`, `@RequestMapping("/auth")`, `@Tag(name = "Auth")`
  - `AuthService` inject

  - `@GetMapping("/github")`
    - `@Operation(summary = "GitHub OAuth URL dondur")`
    - `@ApiResponse(responseCode = "200")`
    - -> `Map.of("authUrl", authService.initiateLogin())`

  - `@GetMapping("/callback")`
    - `@Operation(summary = "OAuth callback - JWT dondur")`
    - `@ApiResponse(responseCode = "200")`
    - `@RequestParam String code`
    - -> `authService.handleCallback(code)`

  - `@PostMapping("/refresh")`
    - `@Operation(summary = "Access token yenile")`
    - `@ApiResponse(responseCode = "200")`
    - `@RequestBody RefreshRequest request`
    - -> `Map.of("accessToken", authService.refresh(request.getRefreshToken()))`

  - `@PostMapping("/logout")`
    - `@Operation(summary = "Cikis yap")`
    - `@ApiResponse(responseCode = "204")`
    - `@RequestBody RefreshRequest request`
    - -> `ResponseEntity.noContent().build()`

### ✅ ADIM 4 Dogrulama

- [x] `mvn compile` hatasiz
- [x] `GET /auth/github` -> GitHub OAuth URL doner
- [x] Swagger UI'da auth endpoint'leri gorunur
- [x] Tarayicida URL'ye gidilir -> GitHub login ekrani acilir (OAuth App kuruluysa)
- [x] Callback calisir -> JWT doner (OAuth App kuruluysa)

---

## ADIM 5 - Repo Modulu

### 5.1 - Model ve DTO'lar

- [ ] `repo/model/Repo.java` olustur:
  - Alanlar: `Long id`, `Long userId`, `String githubRepoId`, `String fullName`, `Boolean isPrivate`, `LocalDateTime lastScannedAt`, `LocalDateTime createdAt`
  - Lombok: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`

- [ ] `repo/dto/RepoDto.java` olustur:
  - Response: id, githubRepoId, fullName, isPrivate, lastScannedAt, createdAt

- [ ] `repo/dto/ConnectRepoRequest.java` olustur:
  - Request: githubRepoId, fullName, isPrivate

### 5.2 - Repository

- [ ] `repo/repository/RepoRepository.java` olustur:
  - `@Repository`, `JdbcTemplate` inject
  - `RowMapper<Repo>` tanimla
  - `List<Repo> findByUserId(Long userId)`:
    ```sql
    SELECT * FROM repos WHERE user_id = ? ORDER BY created_at DESC
    ```
  - `Optional<Repo> findById(Long id)`:
    ```sql
    SELECT * FROM repos WHERE id = ?
    ```
  - `Repo save(Repo repo)`:
    ```sql
    INSERT INTO repos (user_id, github_repo_id, full_name, private)
    VALUES (?, ?, ?, ?)
    RETURNING *
    ```
  - `void deleteById(Long id)`:
    ```sql
    DELETE FROM repos WHERE id = ?
    ```
  - `void updateLastScannedAt(Long repoId)`:
    ```sql
    UPDATE repos SET last_scanned_at = NOW() WHERE id = ?
    ```

### 5.3 - Service

- [ ] `repo/service/RepoService.java` olustur:
  - `@Service`
  - Inject: `RepoRepository`
  - `List<RepoDto> listRepos(Long userId)` -> repo listesi, DTO'ya map et
  - `RepoDto connectRepo(Long userId, ConnectRepoRequest dto)`:
    - Repo olustur, kaydet, DTO dondur
  - `void disconnectRepo(Long userId, Long repoId)`:
    - `findById(repoId)` -> yoksa 404
    - `repo.getUserId() != userId` -> 403 (forbidden)
    - `deleteById(repoId)`

### 5.4 - Controller

- [ ] `repo/controller/RepoController.java` olustur:
  - `@RestController`, `@RequestMapping("/repos")`, `@Tag(name = "Repos")`
  - userId -> `SecurityContextHolder`'dan extract (helper metod)

  - `@GetMapping`
    - `@Operation(summary = "Bagli repolari listele")`
    - -> `List<RepoDto>`

  - `@PostMapping`
    - `@Operation(summary = "Repo bagla")`
    - `@ApiResponse(responseCode = "201")`
    - `@RequestBody ConnectRepoRequest`
    - -> `ResponseEntity.status(201).body(repoDto)`

  - `@DeleteMapping("/{id}")`
    - `@Operation(summary = "Repo baglantisini kes")`
    - `@ApiResponse(responseCode = "204")`
    - -> `ResponseEntity.noContent().build()`

### ✅ ADIM 5 Dogrulama

- [ ] `mvn compile` hatasiz
- [ ] Swagger UI'da JWT ile authorize edilir
- [ ] `POST /repos` ile repo eklenir -> 201
- [ ] `GET /repos` ile repo listelenir
- [ ] `DELETE /repos/{id}` ile repo silinir -> 204
- [ ] Baskasinin reposunu silmeye calismak -> 403

---

## ADIM 6 - Scanner Modulu

### 6.1 - Model

- [ ] `scanner/model/ScanJob.java` olustur:
  - Alanlar: `Long id`, `Long repoId`, `String status`, `LocalDateTime startedAt`, `LocalDateTime finishedAt`, `Integer debtFound`, `LocalDateTime createdAt`
  - Lombok: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`

### 6.2 - DebtParserService (Kritik)

- [ ] `scanner/service/DebtParserService.java` olustur:
  - `@Service`
  - Regex pattern'lari (CASE_INSENSITIVE):
    ```java
    // Tek satir: // TODO: mesaj
    Pattern.compile("//\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", CASE_INSENSITIVE)
    // Hash: # TODO: mesaj
    Pattern.compile("#\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", CASE_INSENSITIVE)
    // Block: * TODO: mesaj
    Pattern.compile("\\*\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", CASE_INSENSITIVE)
    // SQL: -- TODO: mesaj
    Pattern.compile("--\\s*(TODO|FIXME|HACK|XXX)[:\\s]?(.*)", CASE_INSENSITIVE)
    ```
  - `List<DebtDto> parse(String fileContent, String filePath)`:
    1. `fileContent.split("\\n")` ile satirlara bol
    2. Her satiri tum pattern'larla test et
    3. Eslesme varsa `DebtDto` olustur: filePath, lineNo (1-indexed), label (UPPERCASE), content (trim)
    4. Liste dondur

### 6.3 - GitHubFileService

- [ ] `scanner/service/GitHubFileService.java` olustur:
  - `@Service`, `RestTemplate` inject
  - Desteklenen uzantilar (Set):
    ```
    .java, .ts, .tsx, .js, .jsx, .py, .go, .rb, .rs, .kt, .swift, .cs, .cpp, .c, .h
    ```
  - `List<String> listRepoFiles(String repoFullName, String githubToken)`:
    - GET `https://api.github.com/repos/{fullName}/git/trees/main?recursive=1`
    - Fallback: `master` branch dene
    - Response'tan `tree` array'ini parse et
    - Filtre: `type == "blob"` ve desteklenen uzanti
    - Dosya path listesi dondur
  - `String getFileContent(String repoFullName, String filePath, String githubToken)`:
    - GET `https://api.github.com/repos/{fullName}/contents/{filePath}`
    - Header: `Accept: application/vnd.github.v3+json`
    - Response'tan `content` alanini al (Base64 encoded)
    - `Base64.decode()` -> String dondur

### 6.4 - Repository

- [ ] `scanner/repository/ScanJobRepository.java` olustur:
  - `@Repository`, `JdbcTemplate` inject
  - `RowMapper<ScanJob>` tanimla
  - `ScanJob save(ScanJob job)`:
    ```sql
    INSERT INTO scan_jobs (repo_id, status, started_at)
    VALUES (?, 'RUNNING', NOW())
    RETURNING *
    ```
  - `void updateStatus(Long jobId, String status, Integer debtFound)`:
    ```sql
    UPDATE scan_jobs
    SET status = ?, debt_found = ?, finished_at = NOW()
    WHERE id = ?
    ```
  - `Optional<ScanJob> findLatestByRepoId(Long repoId)`:
    ```sql
    SELECT * FROM scan_jobs WHERE repo_id = ?
    ORDER BY created_at DESC LIMIT 1
    ```

### 6.5 - ScanJobProcessor (Async)

- [ ] `scanner/job/ScanJobProcessor.java` olustur:
  - `@Component`
  - Inject: `ScanJobRepository`, `GitHubFileService`, `DebtParserService`, `DebtRepository`, `UserRepository`, `RepoRepository`, `AesEncryptionUtil`
  - `@Async("taskExecutor")`
  - `void processScan(Long repoId, Long userId)`:
    ```
    try:
      1. ScanJob olustur -> status=RUNNING
      2. UserRepository.findById(userId) -> user
      3. AesEncryptionUtil.decrypt(user.getGithubToken()) -> plainToken
      4. RepoRepository.findById(repoId) -> repo
      5. GitHubFileService.listRepoFiles(repo.getFullName(), plainToken) -> filePaths
      6. Her filePath icin:
         a. getFileContent(fullName, filePath, plainToken) -> content
         b. DebtParserService.parse(content, filePath) -> newDebts
         c. allDebts.addAll(newDebts)
      7. DebtRepository.saveAll(repoId, allDebts) -> ON CONFLICT DO NOTHING
      8. DebtRepository.resolveDebts(repoId, currentDebtKeys) -> artik olmayanlara resolved_at
      9. ScanJobRepository.updateStatus(jobId, "DONE", allDebts.size())
      10. RepoRepository.updateLastScannedAt(repoId)
    catch:
      ScanJobRepository.updateStatus(jobId, "FAILED", 0)
      log.error(...)
    ```

### 6.6 - Service ve Controller

- [ ] `scanner/service/ScanService.java` olustur:
  - `@Service`
  - Inject: `RepoRepository`, `ScanJobProcessor`, `ScanJobRepository`
  - `Map<String, Object> startScan(Long repoId, Long userId)`:
    1. Repo sahiplik kontrolu
    2. `scanJobProcessor.processScan(repoId, userId)` (async - hemen doner)
    3. `{ "repoId": repoId, "status": "RUNNING" }` dondur
  - `ScanJob getScanStatus(Long repoId, Long userId)`:
    1. Repo sahiplik kontrolu
    2. `scanJobRepository.findLatestByRepoId(repoId)` -> job

- [ ] `scanner/controller/ScanController.java` olustur:
  - `@RestController`, `@RequestMapping("/scan")`, `@Tag(name = "Scan")`

  - `@PostMapping("/{repoId}")`
    - `@Operation(summary = "Manuel tarama baslat")`
    - `@ApiResponse(responseCode = "202")`
    - -> `ResponseEntity.accepted().body(...)`

  - `@GetMapping("/{repoId}/status")`
    - `@Operation(summary = "Tarama durumunu sorgula")`
    - -> ScanJob bilgileri

### ✅ ADIM 6 Dogrulama

- [ ] `DebtParserServiceTest.java` yazilir ve gecer:
  ```
  Test input:
    "// TODO: implement this\n"
    "# FIXME: broken logic\n"
    "/* HACK: temporary workaround */\n"
    "// normal comment\n"
    "-- XXX: database issue\n"
  Expected: 4 debt bulunur (TODO, FIXME, HACK, XXX)
  ```
- [ ] `mvn compile` hatasiz
- [ ] Swagger'dan repo taramasi baslatilir -> 202
- [ ] Status sorgulanir -> RUNNING, ardindan DONE olur
- [ ] `mvn test` gecer

---

## ADIM 7 - Debt Modulu

### 7.1 - Model ve DTO'lar

- [ ] `debt/model/Debt.java` olustur:
  - Alanlar: `Long id`, `Long repoId`, `String filePath`, `Integer lineNo`, `String label`, `String content`, `LocalDateTime detectedAt`, `LocalDateTime resolvedAt`

- [ ] `debt/dto/DebtDto.java` olustur:
  - Tum Debt alanlari + `repoFullName`

- [ ] `debt/dto/DebtFilterRequest.java` olustur:
  - `Long repoId`, `String label`, `String status` (open/resolved), `int page`, `int size`

- [ ] `debt/dto/DebtStatsDto.java` olustur:
  - `int total`, `int addedThisWeek`, `int resolvedThisWeek`

- [ ] `debt/dto/PageResponse.java` olustur (generic):
  ```java
  @Data @Builder
  public class PageResponse<T> {
      private List<T> content;
      private int page;
      private int size;
      private long totalElements;
      private int totalPages;
  }
  ```

### 7.2 - Repository

- [ ] `debt/repository/DebtRepository.java` olustur:
  - `@Repository`, `JdbcTemplate` inject
  - `RowMapper<Debt>` tanimla
  - `PageResponse<Debt> findByFilter(...)`:
    - Dinamik WHERE clause: repoId, label, status (open = resolved_at IS NULL)
    - userId uzerinden repo sahiplik filtresi: `JOIN repos ON debts.repo_id = repos.id WHERE repos.user_id = ?`
    - Ayri COUNT query
    - `LIMIT ? OFFSET ?`
  - `DebtStatsDto getStats(Long userId, Long repoId)`:
    ```sql
    -- total (open)
    SELECT COUNT(*) FROM debts d
    JOIN repos r ON d.repo_id = r.id
    WHERE r.user_id = ? AND d.resolved_at IS NULL
    [AND d.repo_id = ?]

    -- added this week
    SELECT COUNT(*) ... WHERE d.detected_at >= date_trunc('week', NOW())

    -- resolved this week
    SELECT COUNT(*) ... WHERE d.resolved_at >= date_trunc('week', NOW())
    ```
  - `void saveAll(Long repoId, List<DebtDto> debts)`:
    - `JdbcTemplate.batchUpdate()` ile batch insert
    ```sql
    INSERT INTO debts (repo_id, file_path, line_no, label, content)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT (repo_id, file_path, line_no) DO NOTHING
    ```
  - `void resolveDebts(Long repoId, Set<String> stillPresentKeys)`:
    - Key format: `filePath:lineNo`
    - Mantik: Bu repodaki resolved_at IS NULL olan debt'lerden, `stillPresentKeys`'te olmayanlarin resolved_at'ini NOW() yap

### 7.3 - Service ve Controller

- [ ] `debt/service/DebtService.java` olustur:
  - `listDebts(Long userId, DebtFilterRequest filter)` -> `PageResponse<DebtDto>`
  - `getStats(Long userId, Long repoId)` -> `DebtStatsDto`

- [ ] `debt/controller/DebtController.java` olustur:
  - `@RestController`, `@RequestMapping("/debts")`, `@Tag(name = "Debts")`

  - `@GetMapping`
    - `@Operation(summary = "Borclari listele (filtreli, sayfali)")`
    - Query params: `repoId`, `label`, `status`, `page`, `size`
    - -> `PageResponse<DebtDto>`

  - `@GetMapping("/stats")`
    - `@Operation(summary = "Borc istatistikleri")`
    - Query param: `repoId`
    - -> `DebtStatsDto`

### ✅ ADIM 7 Dogrulama

- [ ] `mvn compile` hatasiz
- [ ] Tarama tamamlandiktan sonra `GET /debts?repoId=1` -> gercek borclar doner
- [ ] `GET /debts?repoId=1&label=TODO&status=open` -> filtrelenmis sonuclar
- [ ] `GET /debts/stats?repoId=1` -> dogru sayilar
- [ ] Sayfalama calisir: `page=0&size=5` -> 5 kayit

---

## ADIM 8 - Report Modulu

### 8.1 - DTO'lar

- [ ] `report/dto/TrendDataPoint.java`:
  - `LocalDate weekStart`, `int totalDebts`, `int newDebts`, `int resolvedDebts`

- [ ] `report/dto/LabelStats.java`:
  - `int todoCount`, `int fixmeCount`, `int hackCount`, `int xxxCount`

- [ ] `report/dto/ModuleDebtInfo.java`:
  - `String modulePath`, `int debtCount`

- [ ] `report/dto/SummaryResponse.java`:
  - `List<TrendDataPoint> trendData`, `LabelStats labelStats`, `List<ModuleDebtInfo> topModules`

### 8.2 - ReportService

- [ ] `report/service/ReportService.java` olustur:
  - `@Service`, `JdbcTemplate` inject
  - `SummaryResponse getSummary(Long userId, Long repoId)`:
    - **Trend verisi** (son 8 hafta):
      ```sql
      SELECT date_trunc('week', detected_at) as week_start,
             COUNT(*) as new_debts
      FROM debts d JOIN repos r ON d.repo_id = r.id
      WHERE r.user_id = ? [AND d.repo_id = ?]
        AND d.detected_at >= NOW() - INTERVAL '8 weeks'
      GROUP BY week_start ORDER BY week_start
      ```
      (resolved icin benzer query)
    - **Label dagilimi**:
      ```sql
      SELECT label, COUNT(*) as cnt
      FROM debts d JOIN repos r ON d.repo_id = r.id
      WHERE r.user_id = ? [AND d.repo_id = ?] AND d.resolved_at IS NULL
      GROUP BY label
      ```
    - **Top 5 modul**:
      ```sql
      SELECT split_part(file_path, '/', 1) as module, COUNT(*) as cnt
      FROM debts d JOIN repos r ON d.repo_id = r.id
      WHERE r.user_id = ? [AND d.repo_id = ?] AND d.resolved_at IS NULL
      GROUP BY module ORDER BY cnt DESC LIMIT 5
      ```

### 8.3 - EmailService

- [ ] `report/service/EmailService.java` olustur:
  - `@Service`
  - `@Value("${resend.api-key}")` ve `@Value("${resend.from-email}")`
  - `RestTemplate` inject
  - `void sendWeeklyReport(User user)`:
    1. Kullanicinin debt verilerini topla
    2. HTML tablo olustur (basit template):
       - Toplam borc sayisi
       - Bu hafta eklenen / cozulen
       - En kotu modul
    3. POST `https://api.resend.com/emails`:
       ```json
       {
         "from": "${from-email}",
         "to": "${user.email}",
         "subject": "Hashmark Weekly Report",
         "html": "<html>...</html>"
       }
       ```
       Header: `Authorization: Bearer ${api-key}`
  - `void sendTestEmail(Long userId)`:
    - Ayni akis, sadece test verisiyle

### 8.4 - WeeklyReportScheduler

- [ ] `report/scheduler/WeeklyReportScheduler.java` olustur:
  - `@Component`
  - Inject: `UserSettingsRepository`, `EmailService`, `UserRepository`
  - `@Scheduled(cron = "0 0 8 * * MON")`
  - `void sendWeeklyReports()`:
    1. `userSettingsRepository.findUsersWithNotifyEnabled()` -> user listesi
    2. Her user icin `emailService.sendWeeklyReport(user)`
    3. Hata olursa logla, digerlerine devam et

### 8.5 - Controller

- [ ] `report/controller/ReportController.java` olustur:
  - `@RestController`, `@RequestMapping("/report")`, `@Tag(name = "Report")`

  - `@GetMapping("/summary")`
    - `@Operation(summary = "Dashboard ozet ve trend verisi")`
    - Query param: `repoId` (optional)
    - -> `SummaryResponse`

  - `@PostMapping("/send-test")`
    - `@Operation(summary = "Test e-postasi gonder")`
    - -> `ResponseEntity.ok().build()`

### ✅ ADIM 8 Dogrulama

- [ ] `mvn compile` hatasiz
- [ ] `GET /report/summary?repoId=1` -> trend, label, modul verisi doner
- [ ] `POST /report/send-test` -> 200 (Resend API key varsa e-posta gider)
- [ ] Scheduler dogru konfigure edilmis (`@Scheduled` cron ifadesi)

---

## ADIM 9 - Settings Modulu ve Son Kontroller

### 9.1 - Settings Modulu

- [ ] `settings/model/UserSettings.java` olustur:
  - Alanlar: `Long id`, `Long userId`, `Boolean emailNotify`, `String notifyDay`, `LocalDateTime createdAt`

- [ ] `settings/dto/UserSettingsDto.java` olustur:
  - `Boolean emailNotify`, `String notifyDay`

- [ ] `settings/repository/UserSettingsRepository.java` olustur:
  - `JdbcTemplate` ile raw SQL
  - `Optional<UserSettings> findByUserId(Long userId)`
  - `UserSettings save(UserSettings settings)` -> INSERT ON CONFLICT UPDATE
  - `List<User> findUsersWithNotifyEnabled()` -> JOIN users, WHERE email_notify = true

- [ ] `settings/service/UserSettingsService.java` olustur:
  - `getSettings(Long userId)` -> yoksa default olustur, dondur
  - `updateSettings(Long userId, UserSettingsDto dto)` -> upsert

- [ ] `settings/controller/SettingsController.java` olustur:
  - `GET /settings` -> `UserSettingsDto`
  - `PUT /settings` -> `UserSettingsDto`

### 9.2 - README.md

- [ ] `README.md` dosyasi olustur:
  - Proje tanimi
  - Gereksinimler: Java 17+, PostgreSQL 16+, Maven
  - Kurulum adimlari:
    1. PostgreSQL'de `hashmark` veritabani olustur
    2. `.env.example`'i `.env` olarak kopyala, degerleri doldur
    3. `mvn spring-boot:run`
  - Env degisken listesi tablosu
  - API endpoint ozeti tablosu
  - Swagger UI linki

### 9.3 - Son Kontrol Listesi

- [ ] `mvn test` - tum testler gecer
- [ ] `mvn spring-boot:run` - uygulama hatasiz ayaga kalkar
- [ ] Swagger UI acilir, tum endpoint'ler listede gorunur
- [ ] JWT ile authorize edilebilir
- [ ] `GET /actuator/health` -> `{ "status": "UP" }`
- [ ] Auth flow uctan uca calisir
- [ ] Tarama flow calisir: repo ekle -> tarama baslat -> borclar listelenir
- [ ] `.env` dosyasi `.gitignore`'da
- [ ] `README.md` eksiksiz
- [ ] Hicbir yerde ORM annotasyonu kullanilmamis (`@Entity`, `@Table`, `@Column` yok)
- [ ] Tum DB erisimi `JdbcTemplate` + raw SQL ile
- [ ] Tum controller metodlarinda `@Operation` ve `@ApiResponse` var
- [ ] Tum endpoint'lerde `userId` JWT'den extract ediliyor

### ✅ ADIM 9 Dogrulama

- [ ] Tum yukaridaki kontrol listesi items'lari ✅
- [ ] Settings endpoint'leri Swagger'da calisir
- [ ] README.md okunabilir ve dogru

---

## Dosya Ozet Tablosu

| # | Dosya | Modul | Tur |
|---|-------|-------|-----|
| 1 | `pom.xml` | root | MODIFY |
| 2 | `application.yml` | root | MODIFY |
| 3 | `application.properties` | root | DELETE |
| 4 | `HashmarkApplication.java` | root | MODIFY |
| 5 | `hashmarkbackend/` | root | DELETE |
| 6 | `.env` | root | MODIFY |
| 7 | `.env.example` | root | MODIFY |
| 8 | `V1__init_schema.sql` | migration | MODIFY |
| 9 | `V2__add_scan_jobs.sql` | migration | MODIFY |
| 10 | `V3__add_user_settings.sql` | migration | MODIFY |
| 11 | `JwtUtil.java` | common/util | NEW |
| 12 | `AesEncryptionUtil.java` | common/util | NEW |
| 13 | `ApiException.java` | common/exception | NEW |
| 14 | `ErrorResponse.java` | common/exception | NEW |
| 15 | `GlobalExceptionHandler.java` | common/exception | NEW |
| 16 | `AsyncConfig.java` | common/config | NEW |
| 17 | `SwaggerConfig.java` | common/config | NEW |
| 18 | `CorsConfig.java` | common/config | NEW |
| 19 | `SecurityConfig.java` | common/config | NEW |
| 20 | `JwtAuthenticationFilter.java` | common/config | NEW |
| 21 | `User.java` | auth/model | NEW |
| 22 | `UserRepository.java` | auth/repository | NEW |
| 23 | `GitHubUserDto.java` | auth/dto | NEW |
| 24 | `LoginResponse.java` | auth/dto | NEW |
| 25 | `RefreshRequest.java` | auth/dto | NEW |
| 26 | `GitHubOAuthService.java` | auth/service | NEW |
| 27 | `AuthService.java` | auth/service | NEW |
| 28 | `AuthController.java` | auth/controller | NEW |
| 29 | `Repo.java` | repo/model | NEW |
| 30 | `RepoDto.java` | repo/dto | NEW |
| 31 | `ConnectRepoRequest.java` | repo/dto | NEW |
| 32 | `RepoRepository.java` | repo/repository | NEW |
| 33 | `RepoService.java` | repo/service | NEW |
| 34 | `RepoController.java` | repo/controller | NEW |
| 35 | `ScanJob.java` | scanner/model | NEW |
| 36 | `DebtParserService.java` | scanner/service | NEW |
| 37 | `GitHubFileService.java` | scanner/service | NEW |
| 38 | `ScanJobRepository.java` | scanner/repository | NEW |
| 39 | `ScanJobProcessor.java` | scanner/job | NEW |
| 40 | `ScanService.java` | scanner/service | NEW |
| 41 | `ScanController.java` | scanner/controller | NEW |
| 42 | `Debt.java` | debt/model | NEW |
| 43 | `DebtDto.java` | debt/dto | NEW |
| 44 | `DebtFilterRequest.java` | debt/dto | NEW |
| 45 | `DebtStatsDto.java` | debt/dto | NEW |
| 46 | `PageResponse.java` | debt/dto | NEW |
| 47 | `DebtRepository.java` | debt/repository | NEW |
| 48 | `DebtService.java` | debt/service | NEW |
| 49 | `DebtController.java` | debt/controller | NEW |
| 50 | `TrendDataPoint.java` | report/dto | NEW |
| 51 | `LabelStats.java` | report/dto | NEW |
| 52 | `ModuleDebtInfo.java` | report/dto | NEW |
| 53 | `SummaryResponse.java` | report/dto | NEW |
| 54 | `ReportService.java` | report/service | NEW |
| 55 | `EmailService.java` | report/service | NEW |
| 56 | `WeeklyReportScheduler.java` | report/scheduler | NEW |
| 57 | `ReportController.java` | report/controller | NEW |
| 58 | `UserSettings.java` | settings/model | NEW |
| 59 | `UserSettingsDto.java` | settings/dto | NEW |
| 60 | `UserSettingsRepository.java` | settings/repository | NEW |
| 61 | `UserSettingsService.java` | settings/service | NEW |
| 62 | `SettingsController.java` | settings/controller | NEW |
| 63 | `JwtUtilTest.java` | test | NEW |
| 64 | `AesEncryptionUtilTest.java` | test | NEW |
| 65 | `DebtParserServiceTest.java` | test | NEW |
| 66 | `README.md` | root | NEW |
