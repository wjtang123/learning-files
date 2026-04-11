package selflearning.abstractandinterface;

import java.util.HashMap;
import java.util.Map;

/**
 * 实战案例：支付系统 —— JDK8 兼容版
 *
 * 改造点（相比原版）：
 *   1. instanceof 模式匹配（JDK16）→ 改为传统 instanceof + 强制转型
 *   2. Map.of()（JDK9）          → 改为手动 put 的 HashMap
 *
 * 用一个真实业务场景，展示抽象类和接口的各自职责，
 * 以及如何组合使用达到最佳设计。
 *
 * 设计思路：
 *   - 抽象类 AbstractPayment：所有支付方式共享的流程和逻辑
 *   - 接口 Refundable：部分支付方式支持退款（不是所有都支持）
 *   - 接口 Installmentable：部分支付方式支持分期
 *   - 具体类：微信支付、信用卡、礼品卡（各有不同能力组合）
 */
public class Abstract2_RealWorld {

    // ============================================================
    // 接口：描述"额外能力"，不是所有支付方式都有
    // ============================================================

    interface Refundable {
        boolean refund(String orderId, double amount);
        double getMaxRefundDays(); // 最长可退款天数
    }

    interface Installmentable {
        int[] getSupportedPeriods();           // 支持的分期数，如 [3, 6, 12]
        double getInterestRate(int period);    // 各期利率
    }

    // ============================================================
    // 抽象类：所有支付方式的"共同本质"
    // ============================================================
    static abstract class AbstractPayment {

        private String paymentName;
        // 模拟订单存储，子类可访问
        protected Map<String, Double> orderRecord = new HashMap<String, Double>();

        public AbstractPayment(String paymentName) {
            this.paymentName = paymentName;
        }

        // ---- 模板方法：支付流程固定，细节各自实现 ----
        // final：子类不能改变这个流程的顺序
        public final boolean pay(String orderId, double amount) {
            System.out.println("\n【" + paymentName + "】支付流程开始");

            // 第一步：参数校验（固定逻辑）
            if (amount <= 0) {
                System.out.println("  ✗ 金额不合法");
                return false;
            }

            // 第二步：余额/额度检查（各支付方式不同）
            if (!checkBalance(amount)) {
                System.out.println("  ✗ 余额或额度不足");
                return false;
            }

            // 第三步：执行扣款（各支付方式不同）
            boolean success = executePayment(orderId, amount);

            // 第四步：记录流水（固定逻辑）
            if (success) {
                orderRecord.put(orderId, amount);
                System.out.println("  ✓ 支付成功，订单：" + orderId + "，金额：¥" + amount);
            } else {
                System.out.println("  ✗ 支付失败");
            }

            // 第五步：发通知（有默认实现，子类可覆盖）
            sendNotification(orderId, success);

            return success;
        }

        // 子类必须实现
        protected abstract boolean checkBalance(double amount);
        protected abstract boolean executePayment(String orderId, double amount);

        // 子类可以覆盖（有默认行为）
        protected void sendNotification(String orderId, boolean success) {
            System.out.println("  → 短信通知已发送");
        }

        // 所有子类共享的工具方法
        protected void logTransaction(String msg) {
            System.out.println("  [日志] " + paymentName + ": " + msg);
        }

        public String getPaymentName() { return paymentName; }
    }

    // ============================================================
    // 具体实现：微信支付（支持退款，不支持分期）
    // ============================================================
    static class WechatPay extends AbstractPayment implements Refundable {

        private double balance;

        public WechatPay(double balance) {
            super("微信支付");
            this.balance = balance;
        }

        @Override
        protected boolean checkBalance(double amount) {
            return balance >= amount;
        }

        @Override
        protected boolean executePayment(String orderId, double amount) {
            balance -= amount;
            logTransaction("扣减余额，剩余：¥" + balance);
            return true;
        }

        @Override
        protected void sendNotification(String orderId, boolean success) {
            // 覆盖默认实现，改成微信消息推送
            System.out.println("  → 微信消息通知已推送");
        }

        // 实现 Refundable 接口
        @Override
        public boolean refund(String orderId, double amount) {
            if (!orderRecord.containsKey(orderId)) {
                System.out.println("  退款失败：订单不存在");
                return false;
            }
            balance += amount;
            System.out.println("  退款成功：¥" + amount + " 已原路退回，余额：¥" + balance);
            return true;
        }

        @Override
        public double getMaxRefundDays() { return 90; }
    }

    // ============================================================
    // 具体实现：信用卡（支持退款 + 支持分期）
    // ============================================================
    static class CreditCard extends AbstractPayment
            implements Refundable, Installmentable {

        private double creditLimit;
        private double usedAmount;
        // JDK8 兼容：用 HashMap 手动初始化，替代 Map.of()
        private static final Map<Integer, Double> INTEREST_RATES;
        static {
            INTEREST_RATES = new HashMap<Integer, Double>();
            INTEREST_RATES.put(3,  0.0);
            INTEREST_RATES.put(6,  0.01);
            INTEREST_RATES.put(12, 0.015);
            INTEREST_RATES.put(24, 0.02);
        }

        public CreditCard(double creditLimit) {
            super("信用卡");
            this.creditLimit = creditLimit;
        }

        @Override
        protected boolean checkBalance(double amount) {
            return (creditLimit - usedAmount) >= amount;
        }

        @Override
        protected boolean executePayment(String orderId, double amount) {
            usedAmount += amount;
            logTransaction("占用额度，剩余额度：¥" + (creditLimit - usedAmount));
            return true;
        }

        @Override
        public boolean refund(String orderId, double amount) {
            usedAmount -= amount;
            System.out.println("  退款成功：¥" + amount + " 已退回，可用额度：¥"
                    + (creditLimit - usedAmount));
            return true;
        }

        @Override
        public double getMaxRefundDays() { return 30; }

        @Override
        public int[] getSupportedPeriods() { return new int[]{3, 6, 12, 24}; }

        @Override
        public double getInterestRate(int period) {
            Double rate = INTEREST_RATES.get(period);
            // 不在表里的期数返回默认利率 2.5%
            return rate != null ? rate : 0.025;
        }
    }

    // ============================================================
    // 具体实现：礼品卡（不支持退款，不支持分期）
    // ============================================================
    static class GiftCard extends AbstractPayment {

        private double balance;
        private String cardNo;

        public GiftCard(String cardNo, double balance) {
            super("礼品卡");
            this.cardNo  = cardNo;
            this.balance = balance;
        }

        @Override
        protected boolean checkBalance(double amount) {
            return balance >= amount;
        }

        @Override
        protected boolean executePayment(String orderId, double amount) {
            balance -= amount;
            logTransaction("卡号" + cardNo + " 余额：¥" + balance);
            return true;
        }
        // 礼品卡不实现 Refundable，不支持退款
        // 不覆盖 sendNotification，使用父类的短信通知
    }

    // ============================================================
    // 支付服务：面向接口/抽象类编程
    // ============================================================
    static class PaymentService {

        // 只依赖抽象类，不关心具体是哪种支付
        public void processPayment(AbstractPayment payment,
                                   String orderId, double amount) {
            payment.pay(orderId, amount);
        }

        // 退款：JDK8 兼容写法，用传统 instanceof + 强制转型
        public void processRefund(AbstractPayment payment,
                                  String orderId, double amount) {
            if (payment instanceof Refundable) {
                // 传统写法：先判断类型，再显式转型
                Refundable refundable = (Refundable) payment;
                System.out.println("\n申请退款...");
                refundable.refund(orderId, amount);
            } else {
                System.out.println("\n【" + payment.getPaymentName() + "】不支持退款");
            }
        }

        // 分期计算：同样用传统 instanceof 写法
        public void showInstallmentPlan(AbstractPayment payment,
                                        double amount, int period) {
            if (payment instanceof Installmentable) {
                Installmentable card = (Installmentable) payment;
                double rate    = card.getInterestRate(period);
                double monthly = amount * (1 + rate * period) / period;
                System.out.printf("%n分期方案：%d期，月利率%.1f%%，每期约¥%.2f%n",
                        period, rate * 100, monthly);
            } else {
                System.out.println(payment.getPaymentName() + " 不支持分期");
            }
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {

        PaymentService service = new PaymentService();

        WechatPay wechat = new WechatPay(500);
        CreditCard card   = new CreditCard(10000);
        GiftCard gift     = new GiftCard("GC001", 200);

        // 统一用 AbstractPayment 处理支付
        service.processPayment(wechat, "ORDER001", 299);
        service.processPayment(card,   "ORDER002", 3000);
        service.processPayment(gift,   "ORDER003", 150);

        System.out.println("\n======= 退款测试 =======");
        service.processRefund(wechat, "ORDER001", 299); // 支持，instanceof 成立
        service.processRefund(gift,   "ORDER003", 150); // 不支持，走 else 分支

        System.out.println("\n======= 分期测试 =======");
        service.showInstallmentPlan(card,   3000, 12); // 支持分期
        service.showInstallmentPlan(wechat, 3000, 12); // 不支持分期

        /*
         * JDK8 vs 新版本的两处主要区别：
         *
         * 1. instanceof 写法：
         *    JDK16+（模式匹配）： if (payment instanceof Refundable r) { r.refund(...) }
         *    JDK8（传统写法）：   if (payment instanceof Refundable) {
         *                            Refundable r = (Refundable) payment;
         *                            r.refund(...)
         *                        }
         *
         * 2. Map 初始化：
         *    JDK9+：  Map.of(3, 0.0, 6, 0.01, 12, 0.015)
         *    JDK8：   static { map = new HashMap<>(); map.put(3, 0.0); ... }
         *
         * 功能完全一致，只是写法冗余一些。
         */
    }
}