package selflearning.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.util.*;

/**
 * Spring Boot + Spring MVC 实战
 *
 * Spring Boot 做了什么？
 *   传统 Spring：需要大量 XML 配置，手动配置 Tomcat，手动管理依赖版本
 *   Spring Boot：约定优于配置，自动配置，内嵌服务器，起步依赖
 *
 * @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan
 *   @Configuration：当前类是配置类
 *   @EnableAutoConfiguration：开启自动配置（核心！）
 *   @ComponentScan：扫描当前包及子包下的所有 @Component
 *
 * 自动配置原理（面试必考）：
 *   Spring Boot 在 jar 包里的 META-INF/spring/
 *   org.springframework.boot.autoconfigure.AutoConfiguration.imports
 *   文件里列出了所有自动配置类（JDK8 时在 spring.factories）
 *   每个自动配置类用 @ConditionalOnXxx 控制是否生效：
 *   @ConditionalOnClass(DataSource.class)  → classpath 有 DataSource 才生效
 *   @ConditionalOnMissingBean              → 用户没有自定义 Bean 才生效
 *   @ConditionalOnProperty(name="xxx")     → 有该配置项才生效
 */

// ============================================================
// 一、Spring Boot 启动类
// ============================================================
@SpringBootApplication
class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        // 就这两行，启动一个完整的 Web 应用！
        // 内嵌 Tomcat，自动扫描 Bean，自动配置 MVC，端口默认8080
    }
}

// ============================================================
// 二、数据模型
// ============================================================

// DTO（Data Transfer Object）：接收请求参数
class CreateUserRequest {
    @NotBlank(message = "用户名不能为空")       // Bean Validation 注解
    @Size(min = 2, max = 20, message = "用户名长度2-20")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
             message = "密码至少8位，包含字母和数字")
    private String password;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Min(value = 1, message = "年龄至少1岁")
    @Max(value = 150, message = "年龄最大150岁")
    private Integer age;

    // Getters and Setters（实际项目用 Lombok @Data 省略）
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
}

// VO（View Object）：返回给前端的数据
class UserVO {
    private Integer id;
    private String  username;
    private String  email;
    private Integer age;

    public UserVO(Integer id, String username, String email, Integer age) {
        this.id = id; this.username = username;
        this.email = email; this.age = age;
    }
    // Getters
    public Integer getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public Integer getAge() { return age; }
}

// 统一响应格式
class ApiResponse<T> {
    private int    code;
    private String message;
    private T      data;

    private ApiResponse(int code, String message, T data) {
        this.code = code; this.message = message; this.data = data;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}

// ============================================================
// 三、RESTful Controller
// ============================================================
@RestController  // = @Controller + @ResponseBody（所有方法返回值自动序列化为 JSON）
@RequestMapping("/api/users")  // 类级别的 URL 前缀
@Validated       // 开启方法级别的参数校验
class UserController {

    // 模拟数据库
    private Map<Integer, UserVO> db = new HashMap<Integer, UserVO>();
    private int nextId = 1;

    // ---- GET：查询 ----

    // GET /api/users → 查询所有用户
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserVO>>> listUsers(
            @RequestParam(defaultValue = "0") int page,   // 查询参数 ?page=0
            @RequestParam(defaultValue = "10") int size) {
        List<UserVO> users = new ArrayList<UserVO>(db.values());
        return ResponseEntity.ok(ApiResponse.success(users));
    }

    // GET /api/users/{id} → 查询单个用户
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserVO>> getUser(
            @PathVariable int id) {      // 路径变量 {id}
        UserVO user = db.get(id);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "用户不存在：" + id));
        }
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    // ---- POST：创建 ----

    // POST /api/users → 创建用户
    @PostMapping
    public ResponseEntity<ApiResponse<UserVO>> createUser(
            @RequestBody @Valid CreateUserRequest req) {  // @Valid 触发参数校验
        UserVO user = new UserVO(nextId++, req.getUsername(),
                                 req.getEmail(), req.getAge());
        db.put(user.getId(), user);
        return ResponseEntity
                .status(HttpStatus.CREATED)  // 201 Created
                .body(ApiResponse.success(user));
    }

    // ---- PUT：全量更新 ----

    // PUT /api/users/{id}
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserVO>> updateUser(
            @PathVariable int id,
            @RequestBody @Valid CreateUserRequest req) {
        if (!db.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        UserVO updated = new UserVO(id, req.getUsername(),
                                    req.getEmail(), req.getAge());
        db.put(id, updated);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    // ---- DELETE：删除 ----

    // DELETE /api/users/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable int id) {
        if (db.remove(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build(); // 204 No Content
    }

    // ---- 请求头、Cookie 等参数 ----
    @GetMapping("/me")
    public ResponseEntity<String> getCurrentUser(
            @RequestHeader("Authorization") String token,    // 请求头
            @CookieValue(value = "sessionId", required = false) String sessionId) {
        return ResponseEntity.ok("token=" + token + ", session=" + sessionId);
    }
}

// ============================================================
// 四、全局异常处理
// ============================================================
@RestControllerAdvice  // 对所有 @RestController 生效的全局增强
class GlobalExceptionHandler {

    // 处理参数校验失败
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Map<String, String>> handleValidationError(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        return ApiResponse.error(400, "参数校验失败");
        // 实际项目会把 errors 也放进响应体
    }

    // 处理业务异常
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBizError(IllegalArgumentException ex) {
        return ApiResponse.error(400, ex.getMessage());
    }

    // 处理所有其他未捕获异常（兜底）
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknownError(Exception ex) {
        // 生产环境要记录日志：log.error("未知异常", ex);
        return ApiResponse.error(500, "服务器内部错误");
    }
}

// ============================================================
// 五、application.properties 关键配置项（注释说明）
// ============================================================
/*
 * # 服务配置
 * server.port=8080
 * server.servlet.context-path=/api
 *
 * # 数据库（以 MySQL + HikariCP 为例）
 * spring.datasource.url=jdbc:mysql://localhost:3306/mydb?useSSL=false&characterEncoding=utf8
 * spring.datasource.username=root
 * spring.datasource.password=123456
 * spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
 * spring.datasource.hikari.maximum-pool-size=20
 * spring.datasource.hikari.minimum-idle=5
 *
 * # MyBatis
 * mybatis.mapper-locations=classpath:mapper/*.xml
 * mybatis.configuration.map-underscore-to-camel-case=true
 *
 * # 日志
 * logging.level.com.example=DEBUG
 * logging.file.name=/logs/app.log
 *
 * # JSON 序列化
 * spring.jackson.date-format=yyyy-MM-dd HH:mm:ss
 * spring.jackson.time-zone=Asia/Shanghai
 * spring.jackson.serialization.fail-on-empty-beans=false
 */

/*
 * MVC 请求处理流程（面试必考）：
 *
 * 1. 请求到达 DispatcherServlet（前端控制器，所有请求的入口）
 * 2. DispatcherServlet 问 HandlerMapping：谁处理这个请求？
 * 3. HandlerMapping 根据 URL 找到对应的 Controller 方法，返回 HandlerExecutionChain
 * 4. DispatcherServlet 找到对应的 HandlerAdapter 执行 Controller 方法
 *    执行前：调用所有拦截器的 preHandle()
 *    执行 Controller 方法
 *    执行后：调用所有拦截器的 postHandle()
 * 5. Controller 返回 ModelAndView（或 @ResponseBody 直接返回数据）
 * 6. DispatcherServlet 找 ViewResolver 解析视图（@ResponseBody 时跳过）
 * 7. 渲染视图，调用拦截器的 afterCompletion()，响应客户端
 *
 * 自动配置原理（面试必考）：
 * @SpringBootApplication → @EnableAutoConfiguration
 * → 读取 META-INF/spring/...AutoConfiguration.imports
 * → 加载所有自动配置类（如 DataSourceAutoConfiguration）
 * → 每个配置类用 @Conditional 注解判断是否生效
 * → classpath 有 mysql-connector-java 且有数据库配置 → 自动创建 DataSource Bean
 * 这就是"约定优于配置"的实现：你加了依赖 = 你需要这个功能 = Spring 自动配好
 */
