package selflearning.pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * 创建型设计模式：单例、工厂、建造者
 *
 * 创建型模式解决的核心问题：
 *   如何创建对象，让创建过程更灵活、更可控，
 *   同时让调用方不依赖具体类，只依赖接口/抽象类
 */
public class Pattern1_Creational {

    // ============================================================
    // 一、单例模式（Singleton）
    // 保证一个类全局只有一个实例，并提供全局访问点
    // 场景：配置管理、连接池、日志、线程池管理器
    // ============================================================

    // 写法一：饿汉式——类加载时就创建，线程安全，简单推荐
    static class ConfigManager {
        // 类加载时就初始化，JVM 保证线程安全
        private static final ConfigManager INSTANCE = new ConfigManager();

        private Map<String, String> config = new HashMap<String, String>();

        private ConfigManager() {
            // 私有构造器：外部无法 new，保证唯一性
            config.put("db.host", "localhost");
            config.put("db.port", "3306");
            System.out.println("ConfigManager 初始化");
        }

        public static ConfigManager getInstance() {
            return INSTANCE;
        }

        public String get(String key) {
            return config.get(key);
        }
    }

    // 写法二：DCL 双重检查锁——懒加载，线程安全，高性能
    // 适合：实例化开销大，且不一定会被用到的场景
    static class ConnectionPool {
        // volatile：禁止指令重排序，防止拿到半初始化对象（上一节讲过）
        private static volatile ConnectionPool instance;
        private int maxSize;

        private ConnectionPool(int maxSize) {
            this.maxSize = maxSize;
            System.out.println("ConnectionPool 初始化，size=" + maxSize);
        }

        public static ConnectionPool getInstance() {
            if (instance == null) {                  // 第一次检查（不加锁）
                synchronized (ConnectionPool.class) {
                    if (instance == null) {          // 第二次检查（加锁后）
                        instance = new ConnectionPool(10);
                    }
                }
            }
            return instance;
        }

        public String getConnection() {
            return "Connection from pool(size=" + maxSize + ")";
        }
    }

    // 写法三：枚举单例——最简洁，防反射破坏，防反序列化破坏
    // Effective Java 推荐，但很多团队不熟悉，酌情使用
    enum AppContext {
        INSTANCE;

        private String version = "1.0.0";

        public String getVersion() { return version; }
        public void setVersion(String v) { version = v; }
    }

    // ============================================================
    // 二、工厂模式
    // 解耦"对象的创建"和"对象的使用"，调用方只认接口
    //
    // 三个层次：
    //   简单工厂：一个工厂方法，根据参数决定创建哪种对象（严格来说不是模式）
    //   工厂方法：每种产品一个工厂类，符合开闭原则
    //   抽象工厂：创建一族相关对象（如 Windows 风格的按钮+文本框）
    // ============================================================

    // 产品接口
    interface Payment {
        boolean pay(double amount);
        String getName();
    }

    // 具体产品
    static class WechatPayment implements Payment {
        @Override public boolean pay(double amount) {
            System.out.println("  微信支付：¥" + amount);
            return true;
        }
        @Override public String getName() { return "微信支付"; }
    }

    static class AlipayPayment implements Payment {
        @Override public boolean pay(double amount) {
            System.out.println("  支付宝：¥" + amount);
            return true;
        }
        @Override public String getName() { return "支付宝"; }
    }

    static class CreditCardPayment implements Payment {
        @Override public boolean pay(double amount) {
            System.out.println("  信用卡：¥" + amount);
            return true;
        }
        @Override public String getName() { return "信用卡"; }
    }

    // 简单工厂：静态方法根据类型创建对象
    // 缺点：新增支付方式要修改这个类（违反开闭原则）
    static class SimplePaymentFactory {
        public static Payment create(String type) {
            if ("wechat".equals(type))     return new WechatPayment();
            if ("alipay".equals(type))     return new AlipayPayment();
            if ("creditcard".equals(type)) return new CreditCardPayment();
            throw new IllegalArgumentException("未知支付类型：" + type);
        }
    }

    // 工厂方法：每种产品一个工厂，新增支付只需新增工厂（符合开闭原则）
    interface PaymentFactory {
        Payment create();
    }

    static class WechatPaymentFactory implements PaymentFactory {
        @Override public Payment create() { return new WechatPayment(); }
    }

    static class AlipayPaymentFactory implements PaymentFactory {
        @Override public Payment create() { return new AlipayPayment(); }
    }

    // 使用工厂方法的好处：调用方只依赖 PaymentFactory 接口
    // 换支付方式时，只需换工厂对象，调用代码不变
    static void processOrder(PaymentFactory factory, double amount) {
        Payment payment = factory.create();
        System.out.println("使用 " + payment.getName() + " 处理订单");
        payment.pay(amount);
    }

    // ============================================================
    // 三、建造者模式（Builder）
    // 分步骤构建复杂对象，最终一次性组装
    // 场景：构造参数很多、部分参数可选、需要对参数做校验
    // Java 中最常见的体现：StringBuilder、Lombok @Builder
    // ============================================================
    static class HttpRequest {
        // 必填参数
        private final String url;
        private final String method;
        // 可选参数
        private final Map<String, String> headers;
        private final String body;
        private final int timeout;
        private final boolean followRedirects;

        // 私有构造器，只能通过 Builder 创建
        private HttpRequest(Builder builder) {
            this.url             = builder.url;
            this.method          = builder.method;
            this.headers         = builder.headers;
            this.body            = builder.body;
            this.timeout         = builder.timeout;
            this.followRedirects = builder.followRedirects;
        }

        @Override
        public String toString() {
            return method + " " + url
                    + " [timeout=" + timeout + "ms"
                    + ", headers=" + headers.size()
                    + ", body=" + (body != null ? "yes" : "no")
                    + ", redirect=" + followRedirects + "]";
        }

        // 静态内部 Builder 类
        static class Builder {
            // 必填
            private final String url;
            private final String method;
            // 可选，有默认值
            private Map<String, String> headers = new HashMap<String, String>();
            private String body = null;
            private int timeout = 5000;
            private boolean followRedirects = true;

            // 构造器只接收必填参数
            public Builder(String url, String method) {
                this.url    = url;
                this.method = method;
            }

            // 每个 setter 返回 this，支持链式调用
            public Builder header(String key, String value) {
                this.headers.put(key, value);
                return this;
            }

            public Builder body(String body) {
                this.body = body;
                return this;
            }

            public Builder timeout(int ms) {
                if (ms <= 0) throw new IllegalArgumentException("timeout 必须大于0");
                this.timeout = ms;
                return this;
            }

            public Builder followRedirects(boolean follow) {
                this.followRedirects = follow;
                return this;
            }

            // build() 做最终校验并创建对象
            public HttpRequest build() {
                if (url == null || url.isEmpty()) {
                    throw new IllegalStateException("url 不能为空");
                }
                if ("POST".equals(method) && body == null) {
                    System.out.println("  警告：POST 请求没有 body");
                }
                return new HttpRequest(this);
            }
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== 单例模式 ===");
        ConfigManager c1 = ConfigManager.getInstance();
        ConfigManager c2 = ConfigManager.getInstance();
        System.out.println("同一个实例？" + (c1 == c2));      // true
        System.out.println("db.host=" + c1.get("db.host"));

        ConnectionPool p1 = ConnectionPool.getInstance();
        ConnectionPool p2 = ConnectionPool.getInstance();
        System.out.println("连接池同一个实例？" + (p1 == p2));  // true
        System.out.println(p1.getConnection());

        AppContext ctx = AppContext.INSTANCE;
        System.out.println("枚举单例版本：" + ctx.getVersion());

        System.out.println("\n=== 简单工厂 ===");
        Payment wechat = SimplePaymentFactory.create("wechat");
        wechat.pay(100);

        System.out.println("\n=== 工厂方法 ===");
        // 只依赖接口，随时可以换工厂
        processOrder(new WechatPaymentFactory(), 200);
        processOrder(new AlipayPaymentFactory(), 300);
        // 新增 CreditCard 工厂，processOrder 代码一行不改
//        processOrder(new PaymentFactory() {
//            public Payment create() { return new CreditCardPayment(); }
//        }, 400);
        processOrder(() -> new CreditCardPayment(), 400);
//        processOrder(CreditCardPayment::new, 400);

        System.out.println("\n=== 建造者模式 ===");
        // 链式调用，清晰知道每个参数的含义
        HttpRequest getReq = new HttpRequest.Builder("https://api.example.com/users", "GET")
                .header("Authorization", "Bearer token123")
                .header("Accept", "application/json")
                .timeout(3000)
                .build();
        System.out.println("GET 请求：" + getReq);

        HttpRequest postReq = new HttpRequest.Builder("https://api.example.com/users", "POST")
                .header("Content-Type", "application/json")
                .body("{\"name\":\"Alice\"}")
                .timeout(5000)
                .followRedirects(false)
                .build();
        System.out.println("POST 请求：" + postReq);

        /*
         * 三个模式的选用场景总结：
         *
         * 单例：全局只能有一个的资源（配置、连接池）
         *       → 优先用枚举单例或饿汉式，DCL 用在懒加载场景
         *
         * 工厂：调用方不想 new 具体类，想面向接口编程
         *       → 简单工厂适合类型少且稳定的情况
         *       → 工厂方法适合需要扩展的情况（新增类型不改老代码）
         *
         * 建造者：构造参数多（>4个）或者有可选参数
         *         → 比重载多个构造器清晰得多
         *         → 比 setter 方法安全（build时统一校验）
         */
    }
}
