package selflearning.microservice; /**
 * 微服务核心概念 + Nacos 注册中心
 *
 * ============================================================
 * 前置要求：
 *   本地安装并启动 Nacos Server（单机模式）：
 *   下载：https://github.com/alibaba/nacos/releases
 *   启动：sh startup.sh -m standalone（Linux/Mac）
 *         startup.cmd -m standalone（Windows）
 *   访问：http://localhost:8848/nacos（账号密码：nacos/nacos）
 * ============================================================
 *
 * 一、为什么需要微服务？
 *
 * 单体应用的痛点（项目达到一定规模后）：
 *   ① 部署慢：改了一个小功能，整个应用重新打包部署（几百MB的jar）
 *   ② 扩容难：订单模块压力大，但只能整体扩容，浪费资源
 *   ③ 技术绑定：整个应用必须用同一种技术栈
 *   ④ 团队协作难：几十人改同一个代码库，合并冲突频繁
 *   ⑤ 稳定性差：一个模块有 bug 可能拖垮整个应用
 *
 * 微服务的好处：
 *   ① 独立部署：只需重新部署变更的服务
 *   ② 独立扩容：哪个服务压力大就扩哪个
 *   ③ 技术多样：不同服务可以用不同语言/框架
 *   ④ 故障隔离：订单服务挂了，用户服务还能正常访问
 *
 * 微服务的代价（没人告诉你的部分）：
 *   ① 网络复杂：服务间调用从函数调用变成 HTTP，有延迟和失败可能
 *   ② 分布式事务：A 服务扣库存，B 服务扣余额，怎么保证一致性？
 *   ③ 运维复杂：10个服务 × 3个实例 = 30个进程要管
 *   ④ 调试困难：一个请求跨5个服务，出了问题怎么定位？
 *
 * 结论：小团队、业务简单时，单体应用更适合。
 *       团队规模达到 20+ 人，业务模块清晰时，才考虑拆微服务。
 */

// ============================================================
// 二、Nacos 注册中心的作用
// ============================================================
/*
 * 问题：服务A 要调用服务B，怎么知道服务B 的地址？
 *
 * 硬编码方式（不可行）：
 *   String url = "http://192.168.1.100:8081/users";
 *   问题：B 的 IP 变了怎么办？B 扩容了怎么办？B 挂了怎么办？
 *
 * 注册中心方案：
 *   服务B 启动时：向 Nacos 登记："我是 user-service，地址是 192.168.1.100:8081"
 *   服务A 调用时：问 Nacos："user-service 的地址是多少？"
 *                 Nacos 返回：["192.168.1.100:8081", "192.168.1.101:8081"]（多实例）
 *   服务A 选一个地址（负载均衡），发起调用
 *
 *   ┌─────────────────────────────────────────────┐
 *   │                   Nacos                      │
 *   │  user-service: [100:8081, 101:8081]         │
 *   │  order-service: [102:8082]                  │
 *   └────────┬─────────────────┬───────────────────┘
 *            │ 注册              │ 查询
 *         服务B               服务A → 调用服务B
 *      (user-service)      (order-service)
 */

// ============================================================
// 三、项目结构（父子模块）
// ============================================================
/*
 * 推荐的多模块 Maven 项目结构：
 *
 * mall-parent/                        ← 父项目（管理版本依赖）
 * ├── pom.xml                         ← 统一管理依赖版本
 * ├── mall-common/                    ← 公共模块（实体类、工具类、API定义）
 * │   └── src/main/java/
 * │       ├── entity/User.java
 * │       └── api/UserApi.java        ← Feign 接口定义
 * ├── mall-user/                      ← 用户服务（独立的 Spring Boot 应用）
 * │   ├── pom.xml
 * │   └── src/main/
 * │       ├── java/.../UserApplication.java
 * │       └── resources/application.yml
 * ├── mall-order/                     ← 订单服务
 * │   ├── pom.xml
 * │   └── src/main/...
 * └── mall-gateway/                   ← 网关服务
 *     └── ...
 *
 * 父 pom.xml 关键配置：
 *
 * <parent>
 *   <groupId>org.springframework.boot</groupId>
 *   <artifactId>spring-boot-starter-parent</artifactId>
 *   <version>3.2.0</version>
 * </parent>
 *
 * <properties>
 *   <spring-cloud.version>2023.0.0</spring-cloud.version>
 *   <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
 * </properties>
 *
 * <dependencyManagement>
 *   <dependencies>
 *     <dependency>
 *       <groupId>org.springframework.cloud</groupId>
 *       <artifactId>spring-cloud-dependencies</artifactId>
 *       <version>${spring-cloud.version}</version>
 *       <type>pom</type>
 *       <scope>import</scope>
 *     </dependency>
 *     <dependency>
 *       <groupId>com.alibaba.cloud</groupId>
 *       <artifactId>spring-cloud-alibaba-dependencies</artifactId>
 *       <version>${spring-cloud-alibaba.version}</version>
 *       <type>pom</type>
 *       <scope>import</scope>
 *     </dependency>
 *   </dependencies>
 * </dependencyManagement>
 */

// ============================================================
// 四、用户服务：注册到 Nacos
// ============================================================

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@SpringBootApplication
// @EnableDiscoveryClient  // Spring Cloud 会自动激活，一般不需要显式写
class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}

/*
 * mall-user 的 application.yml：
 *
 * server:
 *   port: 8081
 *
 * spring:
 *   application:
 *     name: user-service       # 服务名，其他服务通过这个名字找到它
 *   cloud:
 *     nacos:
 *       discovery:
 *         server-addr: localhost:8848   # Nacos 地址
 *         namespace: dev                # 命名空间（不同环境隔离）
 *         group: DEFAULT_GROUP
 *
 * # 依赖（pom.xml）：
 * # spring-cloud-starter-alibaba-nacos-discovery
 */

// 用户服务的 Controller（被其他服务调用的 API）
@RestController
@RequestMapping("/users")
class UserController {

    // 模拟数据库
    private static final Map<Integer, Map<String, Object>> DB = new HashMap<>();
    static {
        Map<String, Object> u1 = new HashMap<>();
        u1.put("id", 1); u1.put("username", "alice"); u1.put("age", 25);
        DB.put(1, u1);
        Map<String, Object> u2 = new HashMap<>();
        u2.put("id", 2); u2.put("username", "bob"); u2.put("age", 30);
        DB.put(2, u2);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUser(@PathVariable Integer id) {
        Map<String, Object> user = DB.get(id);
        if (user == null) {
            throw new RuntimeException("用户不存在：" + id);
        }
        // 返回数据时可以加上服务实例信息，方便验证负载均衡
        user.put("servedBy", "user-service:" + System.getenv("SERVER_PORT"));
        return user;
    }

    @GetMapping
    public List<Map<String, Object>> listUsers() {
        return new ArrayList<>(DB.values());
    }

    // 供其他服务调用的内部接口
    @GetMapping("/internal/{id}")
    public Map<String, Object> getInternal(@PathVariable Integer id) {
        return DB.getOrDefault(id, Collections.emptyMap());
    }
}

/*
 * 启动后，打开 Nacos 控制台 → 服务管理 → 服务列表
 * 应该看到 user-service 已注册，实例数为1
 *
 * 如果启动两个实例（不同端口），实例数变为2，
 * 就可以看到负载均衡效果
 */

class MS1_ConceptNacos {
    public static void main(String[] args) {
        System.out.println("本文件演示微服务核心概念和 Nacos 注册");
        System.out.println("需要：");
        System.out.println("1. 启动本地 Nacos Server");
        System.out.println("2. 在 Spring Boot 项目中引入 nacos-discovery 依赖");
        System.out.println("3. 配置 spring.application.name 和 nacos.discovery.server-addr");
        System.out.println("4. 启动应用，即可在 Nacos 控制台看到服务注册");
    }
}
