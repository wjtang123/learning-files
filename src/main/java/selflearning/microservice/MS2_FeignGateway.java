package selflearning.microservice;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenFeign 服务间调用 + Gateway 网关
 *
 * 服务间调用的演进：
 *   方式一：RestTemplate（手动写 HTTP 请求，代码啰嗦）
 *   方式二：OpenFeign（声明式 HTTP 客户端，像调本地方法一样调远程服务）
 *
 * OpenFeign 的魔法：
 *   你只写一个接口，加几个注解，
 *   Spring 自动生成实现类，把接口调用转换成 HTTP 请求，
 *   还自动集成负载均衡（从 Nacos 拿地址，多实例轮询）
 */

// ============================================================
// 一、订单服务：通过 OpenFeign 调用用户服务
// ============================================================

/*
 * mall-order 的 pom.xml 依赖：
 *   spring-cloud-starter-openfeign
 *   spring-cloud-starter-alibaba-nacos-discovery
 *   spring-cloud-starter-loadbalancer  （负载均衡，OpenFeign 需要）
 *
 * mall-order 的 application.yml：
 *   server:
 *     port: 8082
 *   spring:
 *     application:
 *       name: order-service
 *     cloud:
 *       nacos:
 *         discovery:
 *           server-addr: localhost:8848
 */

// 启动类：必须加 @EnableFeignClients
// @SpringBootApplication
// @EnableFeignClients  // 扫描并激活 @FeignClient 接口
class OrderServiceApplication {
    public static void main(String[] args) {
        // SpringApplication.run(OrderServiceApplication.class, args);
    }
}

// ============================================================
// 二、定义 Feign 客户端接口
// ============================================================

// @FeignClient：声明这是一个 Feign 客户端
// name/value：目标服务的 spring.application.name
// path：目标服务的 URL 前缀（对应 @RequestMapping("/users")）
@FeignClient(name = "user-service", path = "/users",
             fallback = UserClientFallback.class) // 降级实现（见后文）
interface UserClient {

    // 接口方法签名要和目标服务的 Controller 方法对应
    // Spring 会把这个调用翻译成：GET http://user-service/users/{id}
    @GetMapping("/{id}")
    Map<String, Object> getUser(@PathVariable("id") Integer id);

    @GetMapping
    List<Map<String, Object>> listUsers();
}

// 降级实现：user-service 不可用时的兜底返回
@Component
class UserClientFallback implements UserClient {

    @Override
    public Map<String, Object> getUser(Integer id) {
        // 返回默认值，不让整个请求失败
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("id", id);
        fallback.put("username", "未知用户（服务降级）");
        return fallback;
    }

    @Override
    public List<Map<String, Object>> listUsers() {
        return Collections.emptyList();
    }
}

// ============================================================
// 三、订单服务的 Controller（调用用户服务）
// ============================================================

@Service
class OrderService {

    // 注入 Feign 客户端，就像注入本地 Bean 一样
    private final UserClient userClient;

    public OrderService(UserClient userClient) {
        this.userClient = userClient;
    }

    public Map<String, Object> createOrder(Integer userId, String product, double price) {
        // 调用用户服务（底层是 HTTP，但写法像本地调用）
        Map<String, Object> user = userClient.getUser(userId);

        if (user == null || user.isEmpty()) {
            throw new RuntimeException("用户不存在：" + userId);
        }

        // 组装订单数据
        Map<String, Object> order = new HashMap<>();
        order.put("orderId",   "ORD" + System.currentTimeMillis());
        order.put("userId",    userId);
        order.put("username",  user.get("username")); // 来自用户服务
        order.put("product",   product);
        order.put("price",     price);
        order.put("status",    "CREATED");
        return order;
    }
}

@RestController
@RequestMapping("/orders")
class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public Map<String, Object> createOrder(
            @RequestParam Integer userId,
            @RequestParam String product,
            @RequestParam double price) {
        return orderService.createOrder(userId, product, price);
    }

    @GetMapping("/test-feign/{userId}")
    public Map<String, Object> testFeign(@PathVariable Integer userId) {
        // 直接测试 Feign 调用
        Map<String, Object> result = new HashMap<>();
        result.put("calledUser", orderService.createOrder(userId, "测试商品", 99.9));
        return result;
    }
}

/*
 * OpenFeign 常用配置（application.yml）：
 *
 * # 超时设置（防止远程调用长时间阻塞）
 * spring:
 *   cloud:
 *     openfeign:
 *       client:
 *         config:
 *           default:                    # 默认配置，所有 Feign 客户端生效
 *             connect-timeout: 2000     # 连接超时（ms）
 *             read-timeout: 5000        # 读取超时（ms）
 *           user-service:              # 针对特定服务的配置
 *             connect-timeout: 1000
 *             read-timeout: 3000
 *       compression:
 *         request:
 *           enabled: true              # 请求压缩（大请求体时节省带宽）
 *         response:
 *           enabled: true
 *
 * # 开启 Feign 请求/响应日志（调试时用，生产关闭）
 * logging:
 *   level:
 *     com.example.client.UserClient: DEBUG
 *
 * # 配合 Sentinel 降级（后面讲）
 * feign:
 *   sentinel:
 *     enabled: true
 */

// ============================================================
// 四、Gateway 网关
// ============================================================
/*
 * 没有网关时：
 *   客户端 → 直接访问各个服务（要记多个地址和端口）
 *   用户服务：localhost:8081
 *   订单服务：localhost:8082
 *   商品服务：localhost:8083
 *
 * 有了网关：
 *   客户端 → 统一访问网关（localhost:8080）
 *   网关 → 根据路径路由到对应服务
 *   /api/users/** → user-service
 *   /api/orders/** → order-service
 *
 * 网关的额外职责：
 *   ① 认证鉴权：统一在网关验证 Token，不用每个服务都写
 *   ② 限流：控制每秒请求数，保护下游服务
 *   ③ 日志：统一记录所有请求的访问日志
 *   ④ 灰度发布：10% 流量发到新版本，90% 到旧版本
 *
 * mall-gateway 的 application.yml：
 *
 * server:
 *   port: 8080
 *
 * spring:
 *   application:
 *     name: gateway-service
 *   cloud:
 *     nacos:
 *       discovery:
 *         server-addr: localhost:8848
 *     gateway:
 *       routes:
 *         - id: user-route
 *           uri: lb://user-service    # lb:// 表示从 Nacos 负载均衡获取地址
 *           predicates:
 *             - Path=/api/users/**   # 匹配路径
 *           filters:
 *             - StripPrefix=1        # 去掉路径前缀 /api，转发时变成 /users/**
 *
 *         - id: order-route
 *           uri: lb://order-service
 *           predicates:
 *             - Path=/api/orders/**
 *           filters:
 *             - StripPrefix=1
 *
 *       # 全局过滤器（对所有路由生效）
 *       default-filters:
 *         - AddResponseHeader=X-Gateway-Version, 1.0   # 添加响应头
 */

// ============================================================
// 五、自定义 Gateway 过滤器（认证示例）
// ============================================================

// GlobalFilter：对所有路由生效
@Component
class AuthFilter implements GlobalFilter, Ordered {

    // 不需要认证的路径（白名单）
    private static final List<String> WHITE_LIST = Arrays.asList(
            "/api/users/login",
            "/api/users/register"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange,
                              GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 白名单路径直接放行
        if (WHITE_LIST.contains(path)) {
            return chain.filter(exchange);
        }

        // 从请求头取 Token
        String token = exchange.getRequest()
                .getHeaders().getFirst("Authorization");

        if (token == null || !isValidToken(token)) {
            // 未认证，返回 401
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 认证通过，把用户信息放入请求头，传给下游服务
        String userId = parseUserId(token);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-User-Id", userId))
                .build();

        return chain.filter(mutated); // 继续执行后续过滤器和路由
    }

    private boolean isValidToken(String token) {
        // 实际项目：解析 JWT，验证签名和过期时间
        return token.startsWith("Bearer ") && token.length() > 10;
    }

    private String parseUserId(String token) {
        // 实际项目：从 JWT payload 提取 userId
        return "1"; // 简化示例
    }

    @Override
    public int getOrder() {
        return -100; // 数字越小优先级越高，认证过滤器要最先执行
    }
}

class MS2_FeignGateway {
    public static void main(String[] args) {
        System.out.println("=== 微服务调用链路 ===");
        System.out.println("客户端请求：POST http://localhost:8080/api/orders?userId=1&product=手机&price=5999");
        System.out.println();
        System.out.println("① Gateway（8080）收到请求");
        System.out.println("   → AuthFilter 验证 Token");
        System.out.println("   → 匹配路由 /api/orders/** → order-service");
        System.out.println("   → 去掉前缀，转发到 order-service/orders");
        System.out.println();
        System.out.println("② order-service（8082）处理");
        System.out.println("   → OrderController.createOrder()");
        System.out.println("   → Feign 调用 user-service/users/1");
        System.out.println();
        System.out.println("③ user-service（8081）处理");
        System.out.println("   → 返回用户信息");
        System.out.println();
        System.out.println("④ order-service 组装结果，返回给网关");
        System.out.println("⑤ 网关返回给客户端");
    }
}
