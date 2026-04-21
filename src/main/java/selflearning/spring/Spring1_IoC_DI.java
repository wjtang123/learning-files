package selflearning.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Spring Core：IoC 容器 和 依赖注入（DI）
 *
 * ============================================================
 * 注意：这个文件是概念演示，需要在 Spring Boot 项目中运行。
 * 建议在 https://start.spring.io 生成一个空项目，
 * 选择 Spring Web 依赖，把这些概念逐步加进去跑。
 * ============================================================
 *
 * 两个核心概念，必须先搞懂：
 *
 * IoC（Inversion of Control，控制反转）
 *   传统方式：你的代码 new 对象，你控制对象的创建
 *   IoC 方式：你告诉 Spring "我需要一个 UserService"，
 *             Spring 负责创建并给你，你不再 new
 *   本质：对象的创建权从你手里转移到 Spring 容器
 *
 * DI（Dependency Injection，依赖注入）
 *   IoC 的具体实现方式：Spring 创建对象时，
 *   自动把它需要的依赖对象也创建好并"注入"进去
 *
 * 为什么需要 IoC/DI？
 *   没有 IoC 时：
 *     class OrderService {
 *       UserService userService = new UserService();    // 强依赖
 *       EmailService email = new EmailService();        // 强依赖
 *       // UserService 的构造器改了，OrderService 也要改
 *       // 想换 MockUserService 做测试？得改代码
 *     }
 *   有了 IoC：
 *     class OrderService {
 *       @Autowired UserService userService; // Spring 自动注入
 *       // 想换实现类？改配置就行，代码不变
 *       // 测试时注入 Mock 对象，零改动
 *     }
 */

// ============================================================
// 一、Bean 的定义方式
// ============================================================

// 方式一：注解（最常用）
// @Component：通用组件
// @Service：服务层（语义更清晰，和 @Component 功能相同）
// @Repository：数据访问层（额外提供异常转换）
// @Controller：Web 控制层

@Service  // 声明这是一个 Spring Bean，Spring 会扫描并管理它
class UserService {
    public String findUser(int id) {
        return "User_" + id;
    }
}

@Repository
class UserRepository {
    public String query(int id) {
        return "DB_User_" + id;
    }
}

// 方式二：@Bean 注解（适合第三方库的类，无法加 @Component）
@Configuration  // 声明这是一个配置类
class AppConfig {

    // @Bean 方法：Spring 调用这个方法并把返回值注册为 Bean
    @Bean
    public UserRepository userRepository() {
        return new UserRepository();
    }

    // @Bean 可以指定名字（默认是方法名）
    @Bean("primaryUserService")
    public UserService userService() {
        return new UserService();
    }
}

// ============================================================
// 二、依赖注入的三种方式
// ============================================================

@Service
class OrderService {

    // 方式一：字段注入（最简洁，但不推荐——无法在非 Spring 环境使用，不利于测试）
    @Autowired
    private UserService userService;

    // 方式二：构造器注入（推荐！依赖关系清晰，方便测试，字段可以是 final）
    private final UserRepository userRepository;

    @Autowired  // 只有一个构造器时可以省略 @Autowired
    public OrderService(UserRepository userRepository) {
        this.userRepository = userRepository;
        // 优点：不能创建没有依赖的对象，避免空指针
        // 优点：final 字段，线程安全
    }

    // 方式三：Setter 注入（适合可选依赖）
    private EmailService emailService;

    @Autowired(required = false) // required=false：找不到也不报错
    public void setEmailService(EmailService emailService) {
        this.emailService = emailService;
    }

    public String createOrder(int userId) {
        String user = userService.findUser(userId);
        return "Order for " + user;
    }
}

// 占位，避免编译错误
class EmailService {}

// ============================================================
// 三、处理多个实现类（@Qualifier 和 @Primary）
// ============================================================

interface PaymentService {
    String pay(double amount);
}

@Service
@Primary  // 有多个实现时，默认注入这个
class WechatPayService implements PaymentService {
    public String pay(double amount) { return "微信支付 ¥" + amount; }
}

@Service
class AlipayService implements PaymentService {
    public String pay(double amount) { return "支付宝 ¥" + amount; }
}

@Service
class CheckoutService {

    private final PaymentService defaultPayment; // 注入 @Primary 的那个（WechatPay）
    private final PaymentService alipay;

    @Autowired
    public CheckoutService(
            PaymentService defaultPayment,
            @Qualifier("alipayService") PaymentService alipay) { // 指定名字
        this.defaultPayment = defaultPayment;
        this.alipay         = alipay;
    }

    public void checkout(double amount, String type) {
        if ("alipay".equals(type)) {
            System.out.println(alipay.pay(amount));
        } else {
            System.out.println(defaultPayment.pay(amount));
        }
    }
}

// ============================================================
// 四、Bean 的作用域（Scope）
// ============================================================

@Service
// @Scope("singleton")  // 默认：整个容器只有一个实例（单例）
class SingletonBean {
    private int count = 0;
    public int increment() { return ++count; }
}

@Service
@Scope("prototype")  // 每次注入/获取都创建新实例
class PrototypeBean {
    private int count = 0;
    public int increment() { return ++count; }
}

// 其他 Scope（Web 环境）：
// @Scope("request")  → 每个 HTTP 请求一个实例
// @Scope("session")  → 每个 HTTP Session 一个实例

// ============================================================
// 五、@Value 注入配置值
// ============================================================
@Service
class ConfigurableService {

    @Value("${app.name:默认应用名}")      // 从 application.properties 读，没有则用默认值
    private String appName;

    @Value("${server.port:8080}")
    private int port;

    @Value("${app.features:feature1,feature2}")
    private String[] features;             // 自动分割逗号分隔的值

    public void printConfig() {
        System.out.println("应用名：" + appName);
        System.out.println("端口：" + port);
    }
}

// ============================================================
// 六、Bean 的生命周期回调
// ============================================================
@Service
class LifecycleBean {

    @PostConstruct  // Bean 创建并注入完成后调用
    public void init() {
        System.out.println("Bean 初始化完成，可以做连接池预热等操作");
    }

    @PreDestroy    // 容器关闭前调用
    public void destroy() {
        System.out.println("Bean 销毁前，可以释放资源");
    }

    // 等价的另一种写法：
    // @Bean(initMethod = "init", destroyMethod = "destroy")
}

/*
 * 面试必答：
 *
 * Q：IoC 和 DI 的关系？
 * A：IoC 是设计原则（控制权转移给容器），DI 是 IoC 的实现方式（容器注入依赖）。
 *    IoC 是目的，DI 是手段。
 *
 * Q：@Autowired 按什么匹配 Bean？
 * A：先按类型（byType）找，只有一个匹配则直接注入；
 *    有多个匹配时再按名字（byName，变量名）匹配；
 *    还找不到则报错（除非 required=false）。
 *    @Qualifier 可以显式指定 Bean 名字。
 *
 * Q：构造器注入 vs 字段注入，推荐哪个？
 * A：推荐构造器注入：
 *    ① 依赖关系在代码里清晰可见
 *    ② 可以用 final 修饰字段，线程安全
 *    ③ 非 Spring 环境（如单元测试）也能正常创建对象
 *    ④ 强制在对象创建时就提供所有依赖，避免空指针
 *
 * Q：Spring Bean 默认是单例的，线程安全吗？
 * A：不一定。Bean 是单例，但如果 Bean 有可变的实例变量（状态），
 *    多线程并发修改就不安全。
 *    推荐：Bean 设计成无状态的（只有方法，没有可变字段），天然线程安全。
 *    必须有状态时：用 ThreadLocal 或加锁。
 */
