package selflearning.microservice;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 分布式事务 + 链路追踪 + 面试重点 + 完整学习路径
 *
 * 这个文件是微服务入门的"最后一公里"：
 *   ① 分布式事务（最难的问题）
 *   ② 链路追踪（最实用的运维工具）
 *   ③ 面试高频问题汇总
 *   ④ 入门实战路径
 */

// ============================================================
// 一、分布式事务：为什么难？
// ============================================================
/*
 * 单体应用的事务：
 *   @Transactional
 *   void createOrder() {
 *     orderDao.insert(order);      // 同一个数据库
 *     inventoryDao.deduct(item);   // 同一个数据库
 *     // 任何一步失败，数据库回滚，完美！
 *   }
 *
 * 微服务的问题：
 *   createOrder() → 调用 order-service（操作 order_db）
 *               → 调用 inventory-service（操作 inventory_db）
 *               → 调用 payment-service（操作 payment_db）
 *
 *   ① 三个数据库，不在同一个事务里
 *   ② order-service 扣库存成功，payment-service 扣款失败怎么办？
 *   ③ 库存已扣，但订单没创建，怎么回滚到一致状态？
 *
 * 解决方案（三种，按复杂度排序）：
 *
 * 方案A：最终一致性 + 消息队列（推荐！实际最常用）
 *   核心思想：不保证立即一致，但最终一定会一致
 *   实现：业务操作完成后发消息，消费端消费消息执行后续操作，失败重试
 *   适合：对一致性要求不那么严格的场景（如积分发放、通知）
 *
 * 方案B：Seata AT 模式（自动事务，入侵性小）
 *   核心思想：Seata 在数据库层自动管理分布式事务
 *   实现：加 @GlobalTransactional，Seata 自动生成回滚日志
 *   适合：所有服务都用 MySQL + MyBatis 的场景
 *
 * 方案C：TCC（Try-Confirm-Cancel）
 *   核心思想：每个操作分三个阶段，出错走 Cancel
 *   实现：业务代码侵入性强，每个操作要实现三个接口
 *   适合：资金类、高一致性要求的核心业务
 */

// ============================================================
// 二、最终一致性方案（最实用）
// ============================================================
/*
 * 场景：下单流程
 *   ① 创建订单（order-service）
 *   ② 扣减库存（inventory-service）
 *   ③ 扣减余额（payment-service）
 *   ④ 发送物流（logistics-service）
 *
 * 消息队列实现：
 *
 * order-service：
 *   @Transactional
 *   void createOrder(Order order) {
 *     orderDao.insert(order);                    // 本地事务
 *     // 发消息（本地事务保证消息和本地操作原子）
 *     messageProducer.send("ORDER_CREATED", order);
 *   }
 *
 * inventory-service（消费者）：
 *   @RabbitListener(queues = "ORDER_CREATED")
 *   void onOrderCreated(Order order) {
 *     try {
 *       inventoryDao.deduct(order);
 *     } catch (Exception e) {
 *       // 失败：消息重新入队，重试（幂等很重要！）
 *       throw e;
 *     }
 *   }
 *
 * 关键：幂等性
 *   消息可能重复消费，每次处理前检查是否已处理过
 *   if (orderProcessed(orderId)) return; // 已处理，直接返回
 *
 * 本地消息表（最可靠）：
 *   ① 业务操作和消息插入同一个本地事务（绑定）
 *   ② 定时任务扫描未发送的消息，发送到 MQ
 *   ③ 消费确认后，标记消息为已发送
 */

// ============================================================
// 三、Seata AT 模式（代码层面）
// ============================================================
/*
 * 依赖（pom.xml）：
 *   spring-cloud-starter-alibaba-seata
 *
 * application.yml：
 *   seata:
 *     enabled: true
 *     service:
 *       vgroup-mapping:
 *         default_tx_group: default   # 事务组映射
 *
 * 每个数据库需要建 undo_log 表（Seata 存回滚日志）：
 *   CREATE TABLE undo_log (
 *     id            BIGINT(20) NOT NULL AUTO_INCREMENT,
 *     branch_id     BIGINT(20) NOT NULL,
 *     xid           VARCHAR(100) NOT NULL,
 *     context       VARCHAR(128) NOT NULL,
 *     rollback_info LONGBLOB NOT NULL,
 *     log_status    INT(11) NOT NULL,
 *     log_created   DATETIME NOT NULL,
 *     log_modified  DATETIME NOT NULL,
 *     PRIMARY KEY (id)
 *   );
 */

// 使用 @GlobalTransactional（代替 @Transactional）
@Service
class OrderTransactionService {

    // @GlobalTransactional：Seata 管理跨服务的分布式事务
    // 和 @Transactional 用法完全一样，但跨越多个数据库
    // @GlobalTransactional(name = "createOrder", rollbackFor = Exception.class)
    @Transactional // 先用本地事务演示，实际用 @GlobalTransactional
    public String createOrder(Integer userId, Integer productId, int qty) {
        // 第一步：扣库存（调用 inventory-service）
        // inventoryClient.deduct(productId, qty);
        System.out.println("  扣减库存：商品" + productId + " x" + qty);

        // 第二步：扣余额（调用 payment-service）
        // paymentClient.deduct(userId, qty * 99.9);
        System.out.println("  扣减余额：用户" + userId);

        // 第三步：创建订单（本地操作）
        // orderDao.insert(new Order(userId, productId, qty));
        String orderId = "ORD" + System.currentTimeMillis();
        System.out.println("  创建订单：" + orderId);

        // 如果任何一步失败（抛异常），Seata 自动回滚所有服务的操作
        return orderId;
    }
}

// ============================================================
// 四、链路追踪（SkyWalking）
// ============================================================
/*
 * 问题：一个请求经过 gateway → order-service → user-service → inventory-service
 *       某个服务响应慢，怎么找到是哪里慢？
 *       某个请求报错，怎么看完整的调用链？
 *
 * SkyWalking（无代码侵入，推荐）：
 *   通过 Java Agent 自动拦截，不需要修改业务代码
 *
 *   1. 下载 SkyWalking Agent：https://skywalking.apache.org/downloads/
 *
 *   2. 启动服务时加 JVM 参数：
 *      java -javaagent:/path/to/skywalking-agent.jar
 *           -Dskywalking.agent.service_name=order-service
 *           -Dskywalking.collector.backend_service=localhost:11800
 *           -jar order-service.jar
 *
 *   3. 访问 SkyWalking UI（http://localhost:8080）可以看到：
 *      - 所有服务的拓扑图（哪个服务调用哪个）
 *      - 每个接口的 P99/P95 响应时间
 *      - 完整的调用链（哪一步慢、哪一步报错）
 *      - 报错的完整堆栈信息
 *
 * Sleuth + Zipkin（代码侵入，Spring Cloud 官方）：
 *   依赖：spring-cloud-starter-sleuth + zipkin
 *   会自动在请求头加 traceId/spanId，串联调用链
 *   适合：已有 Spring Cloud Sleuth 的老项目
 */

// ============================================================
// 五、面试高频问题
// ============================================================
/*
 * Q：微服务和单体应用怎么选？
 * A：团队小（< 20人）、业务简单 → 单体，开发运维成本低。
 *    团队大、业务复杂、需要独立扩容 → 微服务。
 *    不要一开始就搞微服务，先单体，需要时再拆。
 *
 * Q：服务注册发现的原理？
 * A：服务启动时向注册中心（Nacos）上报：服务名、IP、端口。
 *    调用方向注册中心查询服务名对应的实例列表。
 *    负载均衡（LoadBalancer）从列表中选一个实例。
 *    注册中心通过心跳检测实例健康状态，下线不健康实例。
 *
 * Q：Feign 的工作原理？
 * A：@EnableFeignClients 扫描 @FeignClient 注解的接口，
 *    通过动态代理生成实现类。调用接口方法时，代理类
 *    把方法参数和注解转换成 HTTP 请求，通过 LoadBalancer
 *    选择实例，发起 HTTP 调用，把响应反序列化为返回值。
 *
 * Q：什么是服务雪崩？怎么解决？
 * A：下游服务响应慢 → 上游线程池堆积 → 上游也慢 → 连锁崩溃。
 *    解决：
 *    限流（Flow Control）：超过阈值直接拒绝，保护下游
 *    熔断（Circuit Breaker）：失败率高时快速失败，不等超时
 *    降级（Fallback）：熔断时返回兜底数据，保证基本可用
 *    隔离（Bulkhead）：用独立线程池处理不同服务调用，互不影响
 *
 * Q：分布式事务有哪些解决方案？
 * A：① 最终一致性 + 消息队列：业务发消息，消费端重试直到成功（最常用）
 *    ② Seata AT：自动管理，对代码侵入性小，适合同构技术栈
 *    ③ TCC：Try-Confirm-Cancel，侵入性强，适合高一致性核心业务
 *    ④ Saga：长事务拆成多个本地事务，正向操作 + 补偿操作
 *
 * Q：Gateway 网关的作用？
 * A：统一入口（客户端只知道网关地址）、路由转发、认证鉴权、
 *    限流、日志、协议转换、灰度发布。
 *    好处：把公共逻辑从各服务移到网关，减少重复代码。
 *
 * Q：Nacos 和 Eureka 的区别？
 * A：Eureka：Spring Cloud 官方，AP（可用性优先），不支持配置中心。
 *    Nacos：阿里开源，CP/AP 可切换，同时支持注册中心 + 配置中心，
 *           支持健康检查更精细，国内使用更广泛。
 */

// ============================================================
// 六、完整入门路径
// ============================================================
/*
 * 第一周：环境搭建 + Nacos 注册
 *   1. 安装 Nacos Server，启动单机模式
 *   2. 创建两个 Spring Boot 服务（user-service、order-service）
 *   3. 两个服务都注册到 Nacos
 *   4. 在 Nacos 控制台验证服务注册成功
 *
 * 第二周：服务间调用 + 网关
 *   1. order-service 通过 OpenFeign 调用 user-service
 *   2. 创建 gateway-service，配置路由
 *   3. 通过网关访问两个服务
 *   4. 测试 user-service 停机后，Feign 的 fallback 是否生效
 *
 * 第三周：配置中心 + 限流熔断
 *   1. 把配置迁移到 Nacos 配置中心
 *   2. 验证热更新：修改 Nacos 配置，服务不重启配置生效
 *   3. 安装 Sentinel 控制台
 *   4. 配置限流规则，压测验证
 *   5. 配置熔断规则，模拟下游失败验证
 *
 * 第四周：进阶
 *   1. 分布式事务：理解最终一致性方案，用 RabbitMQ 实现
 *   2. 链路追踪：接入 SkyWalking，查看调用链
 *   3. Docker 化：把每个服务打成 Docker 镜像，用 docker-compose 启动
 *
 * 推荐学习资源：
 *   黑马程序员 SpringCloud 课程（B站免费，最系统）
 *   Spring Cloud Alibaba 官方文档：https://sca.aliyun.com/
 *   Seata 官方文档：https://seata.apache.org/
 */

class MS4_TransactionTracing {
    public static void main(String[] args) {
        System.out.println("=== 分布式事务方案选择 ===");
        System.out.println("积分、通知、日志 → 最终一致性 + MQ（最常用）");
        System.out.println("普通业务下单  → Seata AT（简单，自动）");
        System.out.println("资金转账核心  → TCC（复杂，可靠）");
        System.out.println();
        System.out.println("=== 入门建议 ===");
        System.out.println("先跑通 Nacos + Feign + Gateway，这是最核心的链路");
        System.out.println("Sentinel 和分布式事务是进阶内容，可以后续深入");
        System.out.println("不要急着学所有组件，先把核心链路跑通再说");
    }
}
