package selflearning.microservice;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Nacos 配置中心 + Sentinel 限流熔断
 *
 * 本文件涵盖：
 *   ① Nacos 配置中心：统一管理所有服务的配置，热更新
 *   ② Sentinel：保护服务不被流量打垮，实现服务降级
 */

// ============================================================
// 一、Nacos 配置中心
// ============================================================
/*
 * 痛点：微服务有几十个服务，每个都有 application.yml，
 *       要改一个数据库密码，得改几十个文件重新部署。
 *
 * Nacos 配置中心的方案：
 *   把配置集中存在 Nacos，服务启动时从 Nacos 拉取配置。
 *   配置变更时，Nacos 推送给所有服务，无需重启（热更新）。
 *
 * 依赖（pom.xml）：
 *   spring-cloud-starter-alibaba-nacos-config
 *
 * bootstrap.yml（优先于 application.yml 加载）：
 *
 * spring:
 *   application:
 *     name: user-service
 *   cloud:
 *     nacos:
 *       config:
 *         server-addr: localhost:8848
 *         namespace: dev
 *         group: DEFAULT_GROUP
 *         file-extension: yaml        # 配置文件格式
 *         # 加载的配置文件：${spring.application.name}.${file-extension}
 *         # 即：user-service.yaml
 *
 *         # 加载共享配置（多个服务共用，如数据库连接）
 *         shared-configs:
 *           - data-id: common-db.yaml
 *             group: COMMON
 *             refresh: true           # 是否支持热更新
 *
 *         # 加载扩展配置（比共享配置优先级高）
 *         extension-configs:
 *           - data-id: user-service-ext.yaml
 *             group: DEFAULT_GROUP
 *             refresh: true
 *
 * 在 Nacos 控制台创建配置：
 *   Data ID: user-service.yaml
 *   Group: DEFAULT_GROUP
 *   内容（YAML格式）：
 *     app:
 *       max-retry: 3
 *       welcome-message: "Hello from Nacos"
 *     feature:
 *       new-user-gift: true
 */

// 使用 @RefreshScope：配置变更时自动刷新这个 Bean
@RefreshScope
@RestController
@RequestMapping("/config-demo")
class ConfigDemoController {

    // 从 Nacos 配置中心读取，支持热更新
    @Value("${app.max-retry:3}")
    private int maxRetry;

    @Value("${app.welcome-message:Default Message}")
    private String welcomeMessage;

    @Value("${feature.new-user-gift:false}")
    private boolean newUserGift;

    @GetMapping
    public Map<String, Object> showConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("maxRetry", maxRetry);
        config.put("welcomeMessage", welcomeMessage);
        config.put("newUserGift", newUserGift);
        config.put("tip", "修改 Nacos 中的配置，刷新此接口，值会自动更新");
        return config;
    }
    /*
     * 验证热更新：
     *   1. 启动服务，访问 /config-demo，看到初始值
     *   2. 在 Nacos 控制台修改配置并发布
     *   3. 再次访问 /config-demo，值自动变了（无需重启服务）
     *
     * @RefreshScope 的原理：
     *   Nacos 配置变更 → 触发 RefreshEvent → Spring 重新创建被 @RefreshScope 标注的 Bean
     *   注意：不要在 @RefreshScope 的 Bean 里做耗时的初始化操作
     */
}

// ============================================================
// 二、Sentinel 限流熔断
// ============================================================
/*
 * 为什么需要 Sentinel？
 *
 * 雪崩效应：
 *   order-service 调用 user-service
 *   user-service 响应变慢（比如 DB 压力大，响应 5 秒）
 *   order-service 的请求全部堵在等待 user-service
 *   order-service 的线程池满了
 *   调用 order-service 的 gateway 也被拖慢
 *   最终整个系统崩溃
 *
 * Sentinel 的解法：
 *   ① 限流：每秒最多处理 100 个请求，超出的直接拒绝
 *   ② 熔断：user-service 失败率超过 50%，断开调用，快速失败
 *   ③ 降级：熔断期间调用降级方法，返回兜底数据
 *
 * 依赖（pom.xml）：
 *   spring-cloud-starter-alibaba-sentinel
 *
 * application.yml：
 *   spring:
 *     cloud:
 *       sentinel:
 *         transport:
 *           dashboard: localhost:8080  # Sentinel 控制台地址
 *           port: 8719                 # 和控制台通信的端口
 *         eager: true                  # 启动时主动上报
 */

@Service
class ProductService {

    // @SentinelResource：标注资源名，配置限流/熔断规则
    // value：资源名（在 Sentinel 控制台配置规则时用这个名字）
    // blockHandler：被限流/熔断时调用的方法
    // fallback：业务异常时的降级方法（fallback 和 blockHandler 区别：
    //           blockHandler 处理 Sentinel 控制的 BlockException
    //           fallback 处理业务代码抛出的 Throwable）
    @SentinelResource(value = "getProduct",
                      blockHandler = "getProductBlocked",
                      fallback = "getProductFallback")
    public Map<String, Object> getProduct(Integer id) {
        // 模拟偶发的慢请求
        if (id % 5 == 0) {
            try { Thread.sleep(2000); } catch (InterruptedException e) {}
        }
        // 模拟偶发的异常
        if (id % 7 == 0) {
            throw new RuntimeException("商品服务偶发异常");
        }

        Map<String, Object> product = new HashMap<>();
        product.put("id", id);
        product.put("name", "商品" + id);
        product.put("price", 99.9 * id);
        return product;
    }

    // blockHandler：被 Sentinel 限流或熔断时调用
    // 必须和原方法同一个类，参数列表最后多一个 BlockException
    public Map<String, Object> getProductBlocked(Integer id, BlockException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", "系统繁忙，请稍后再试");
        result.put("blocked", true);
        result.put("reason", ex.getClass().getSimpleName()); // FlowException/DegradeException
        return result;
    }

    // fallback：业务异常（非 BlockException）时调用
    public Map<String, Object> getProductFallback(Integer id, Throwable t) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("name", "商品信息暂时不可用");
        result.put("fallback", true);
        result.put("error", t.getMessage());
        return result;
    }
}

// ============================================================
// 三、Sentinel 规则（代码配置方式，也可在控制台配置）
// ============================================================
/*
 * 通常在控制台配置，这里演示代码配置方式（了解原理）：
 *
 * import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
 * import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
 * import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
 * import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
 *
 * @Configuration
 * class SentinelConfig {
 *
 *   @PostConstruct
 *   public void initRules() {
 *
 *     // 限流规则：getProduct 资源每秒最多 100 次 QPS
 *     List<FlowRule> flowRules = new ArrayList<>();
 *     FlowRule flowRule = new FlowRule();
 *     flowRule.setResource("getProduct");
 *     flowRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
 *     flowRule.setCount(100);                      // QPS 阈值
 *     flowRules.add(flowRule);
 *     FlowRuleManager.loadRules(flowRules);
 *
 *     // 熔断规则：异常比例超过 50%，熔断 10 秒
 *     List<DegradeRule> degradeRules = new ArrayList<>();
 *     DegradeRule degradeRule = new DegradeRule();
 *     degradeRule.setResource("getProduct");
 *     degradeRule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
 *     degradeRule.setCount(0.5);                   // 50% 异常率触发熔断
 *     degradeRule.setTimeWindow(10);               // 熔断持续 10 秒
 *     degradeRule.setMinRequestAmount(5);          // 至少 5 次请求才统计
 *     degradeRules.add(degradeRule);
 *     DegradeRuleManager.loadRules(degradeRules);
 *   }
 * }
 */

// ============================================================
// 四、Sentinel 控制台（推荐方式）
// ============================================================
/*
 * 下载 Sentinel 控制台 jar：
 *   https://github.com/alibaba/Sentinel/releases
 *
 * 启动：
 *   java -jar sentinel-dashboard-1.8.x.jar
 *   访问：http://localhost:8080（账号密码：sentinel/sentinel）
 *
 * 在控制台可以：
 *   实时查看：每秒请求数、响应时间、异常率
 *   动态配置：流量控制规则、熔断降级规则，立即生效，无需重启
 *
 * 规则持久化（生产必备）：
 *   默认规则存在内存，服务重启就没了
 *   需要把规则持久化到 Nacos：
 *
 *   <dependency>
 *     <groupId>com.alibaba.csp</groupId>
 *     <artifactId>sentinel-datasource-nacos</artifactId>
 *   </dependency>
 *
 *   spring:
 *     cloud:
 *       sentinel:
 *         datasource:
 *           flow:                      # 规则名（自定义）
 *             nacos:
 *               server-addr: localhost:8848
 *               data-id: ${spring.application.name}-flow-rules
 *               group-id: SENTINEL_GROUP
 *               data-type: json
 *               rule-type: flow        # 规则类型：flow/degrade/authority
 */

// ============================================================
// 五、Feign + Sentinel 集成（更实用的方式）
// ============================================================
/*
 * 比 @SentinelResource 更常用的方式：
 * Feign 客户端接口的 fallback 类
 * （在 MS2_FeignGateway.java 的 UserClientFallback 里已经演示）
 *
 * 配置：
 *   feign:
 *     sentinel:
 *       enabled: true   # 开启 Feign 和 Sentinel 的集成
 *
 * 效果：
 *   user-service 不可用时，自动调用 UserClientFallback.getUser()
 *   不需要在每个方法上写 @SentinelResource
 */

class MS3_NacosConfigSentinel {
    public static void main(String[] args) {
        System.out.println("=== 配置中心热更新验证步骤 ===");
        System.out.println("1. 启动 Nacos，在控制台创建配置 user-service.yaml");
        System.out.println("2. 启动服务，访问 /config-demo 看到初始值");
        System.out.println("3. 在 Nacos 控制台修改配置，点发布");
        System.out.println("4. 再次访问 /config-demo，值自动更新（无需重启）");
        System.out.println();
        System.out.println("=== Sentinel 熔断验证步骤 ===");
        System.out.println("1. 启动 Sentinel 控制台");
        System.out.println("2. 启动服务，触发几次请求后在控制台可以看到 getProduct 资源");
        System.out.println("3. 在控制台配置限流规则：QPS=5");
        System.out.println("4. 快速刷新接口超过5次/秒，触发限流，返回 blockHandler 的内容");
        System.out.println("5. 配置熔断规则：异常比例50%，访问 id=7 的倍数多次触发熔断");
    }
}
