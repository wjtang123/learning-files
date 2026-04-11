package selflearning.multithread;

/**
 * 创建线程方式一：继承 Thread 类
 *
 * 核心思路：
 *   1. 创建一个类，继承 Thread
 *   2. 重写 run() 方法，把"线程要做的事"写进去
 *   3. 创建该类的对象，调用 start() 启动线程
 *
 * 注意：调用 start() 而不是 run()！
 *   - start() → JVM 开启新线程，新线程去调用 run()
 *   - run()   → 只是普通方法调用，不会开新线程，还是在主线程执行
 */
public class Method1_ExtendThread {

    // 第一步：定义一个类继承 Thread
    static class MyThread extends Thread {

        private String name; // 给线程起个名字，方便区分

        public MyThread(String name) {
            this.name = name;
        }

        // 第二步：重写 run() 方法，这里是线程实际执行的代码
        @Override
        public void run() {
            for (int i = 1; i <= 5; i++) {
                System.out.println(name + " 正在执行，第 " + i + " 次");

                try {
                    // 让线程睡 200ms，模拟耗时操作，也让打印结果更明显
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(name + " 执行完毕！");
        }
    }

    public static void main(String[] args) {
        System.out.println("主线程开始，线程名：" + Thread.currentThread().getName());

        // 第三步：创建线程对象
        MyThread t1 = new MyThread("线程-A");
        MyThread t2 = new MyThread("线程-B");

        // 第四步：调用 start() 启动线程（不是 run()！）
        t1.start();
        t2.start();

        // 主线程继续往下走，不会等 t1/t2 结束
        System.out.println("主线程继续执行，不等待子线程...");

        /*
         * 运行后观察：
         *   - 线程-A 和 线程-B 的打印顺序是交替的、随机的
         *   - 每次运行结果可能不同，这就是多线程的"并发"特性
         *   - 主线程的最后一句可能出现在任意位置
         */
    }
}
