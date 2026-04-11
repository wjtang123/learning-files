package selflearning.multithread;

/**
 * 创建线程方式二：实现 Runnable 接口
 *
 * 为什么更推荐这种方式？
 *   1. Java 是单继承的，继承了 Thread 就不能再继承其他类
 *      但 Runnable 是接口，可以同时实现多个接口
 *   2. 任务（Runnable）和线程（Thread）分离，更灵活
 *      同一个 Runnable 对象可以被多个线程共享（见下方示例）
 *   3. 更符合"组合优于继承"的设计原则
 */
public class Method2_ImplementRunnable {

    // 第一步：实现 Runnable 接口
    static class MyTask implements Runnable {

        private String taskName;

        public MyTask(String taskName) {
            this.taskName = taskName;
        }

        // 第二步：实现 run() 方法
        @Override
        public void run() {
            for (int i = 1; i <= 5; i++) {
                // Thread.currentThread().getName() 获取当前线程的名字
                System.out.println("[" + Thread.currentThread().getName() + "] "
                        + taskName + " 执行第 " + i + " 次");
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 基本用法 ===");

        // 第三步：创建 Runnable 任务对象
        MyTask task1 = new MyTask("下载任务");
        MyTask task2 = new MyTask("上传任务");

        // 第四步：把任务交给 Thread，再 start()
        // Thread 构造器第二个参数可以给线程命名
        Thread t1 = new Thread(task1, "线程-下载");
        Thread t2 = new Thread(task2, "线程-上传");

        t1.start();
        t2.start();

        // 等 t1、t2 都结束再继续（join() 后面阶段会详细讲）
        t1.join();
        t2.join();

        // -----------------------------------------------
        System.out.println("\n=== 进阶：同一个 Runnable 被多个线程共享 ===");

        // 同一个 task，交给三个线程执行
        // 应用场景：抢票系统，多个窗口（线程）共享同一批票（任务）
        MyTask sharedTask = new MyTask("共享任务");

        Thread ta = new Thread(sharedTask, "窗口-1");
        Thread tb = new Thread(sharedTask, "窗口-2");
        Thread tc = new Thread(sharedTask, "窗口-3");

        ta.start();
        tb.start();
        tc.start();

        ta.join();
        tb.join();
        tc.join();

        // -----------------------------------------------
        System.out.println("\n=== 进阶：Lambda 简写（Java 8+）===");

        // Runnable 是函数式接口（只有一个抽象方法），
        // 可以用 Lambda 表达式代替，省去单独定义类
        Thread t = new Thread(() -> {
            for (int i = 1; i <= 3; i++) {
                System.out.println("Lambda线程 执行第 " + i + " 次");
            }
        }, "Lambda线程");

        t.start();
        t.join();

        System.out.println("所有线程执行完毕");

        /*
         * 运行后观察：
         *   - "共享任务"部分，三个窗口的输出会交替出现
         *   - Lambda 写法和定义类效果完全一样，只是更简洁
         */
    }
}
