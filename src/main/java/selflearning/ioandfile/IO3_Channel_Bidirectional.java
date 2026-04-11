package selflearning.ioandfile;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;

/**
 * FileChannel 双向性演示
 *
 * 核心：同一个 FileChannel 对象，既能读也能写，
 *       还能通过 position() 跳到文件任意位置操作。
 *
 * 这是流（Stream）做不到的：
 *   流只能顺序读或顺序写，不能随意跳转位置
 *   Channel 可以随机访问，读完再写，写完再读，想去哪里去哪里
 */
public class IO3_Channel_Bidirectional {

    public static void main(String[] args) throws Exception {
        String path = "bidirectional_test.txt";

        // ============================================================
        // 关键：用 RandomAccessFile("rw") 打开文件
        // FileInputStream  → 只读 channel
        // FileOutputStream → 只写 channel
        // RandomAccessFile("rw") → 读写都行的 channel ← 这才是双向
        // ============================================================
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        FileChannel channel = raf.getChannel();

        // ============================================================
        // 第一步：向文件写入内容（写操作）
        // ============================================================
        System.out.println("=== 第一步：写入初始内容 ===");

        String content = "AAAA BBBB CCCC DDDD";
        //                0123456789...
        //                每段4字节，空格隔开

        ByteBuffer writeBuf = ByteBuffer.wrap(
                content.getBytes(StandardCharsets.UTF_8));

        channel.write(writeBuf);  // 写完后 position 在末尾（19）
        System.out.println("写入：" + content);
        System.out.println("写后 position：" + channel.position()); // 19

        // ============================================================
        // 第二步：不关闭 channel，直接跳回头部读（体现双向性）
        // ============================================================
        System.out.println("\n=== 第二步：跳回头部，读取全部内容 ===");

        // position(0)：跳回文件开头，就像把光标移到第一个字符
        // 流做不到这一步——流只能从头读到尾，不能反向跳转
        channel.position(0);
        System.out.println("跳回后 position：" + channel.position()); // 0

        ByteBuffer readBuf = ByteBuffer.allocate((int) channel.size());
        channel.read(readBuf);
        readBuf.flip();
        System.out.println("读到内容：" + StandardCharsets.UTF_8.decode(readBuf));

        // ============================================================
        // 第三步：跳到文件中间，覆盖写（随机写）
        // 把第二段 "BBBB" 改成 "XXXX"
        // ============================================================
        System.out.println("\n=== 第三步：跳到第5字节，把 BBBB 改成 XXXX ===");

        // "AAAA " 占5个字节（4字母+1空格），所以 BBBB 从第5字节开始
        channel.position(5);
        ByteBuffer patchBuf = ByteBuffer.wrap(
                "XXXX".getBytes(StandardCharsets.UTF_8));
        channel.write(patchBuf);  // 从 position=5 处开始写，覆盖原来的 BBBB

        System.out.println("在 position=5 处写入 XXXX");

        // ============================================================
        // 第四步：再次跳回头部，读取修改后的完整内容
        // ============================================================
        System.out.println("\n=== 第四步：再次读取，验证修改生效 ===");

        channel.position(0);      // 又跳回头部
        ByteBuffer verifyBuf = ByteBuffer.allocate((int) channel.size());
        channel.read(verifyBuf);
        verifyBuf.flip();
        System.out.println("修改后内容：" + StandardCharsets.UTF_8.decode(verifyBuf));
        // 期望输出：AAAA XXXX CCCC DDDD

        // ============================================================
        // 第五步：只读取文件中间一段（随机读）
        // ============================================================
        System.out.println("\n=== 第五步：只读取 CCCC（从第10字节开始，读4字节）===");

        // "AAAA XXXX " 占10字节，所以 CCCC 从第10字节开始
        channel.position(10);
        ByteBuffer partBuf = ByteBuffer.allocate(4);
        channel.read(partBuf);
        partBuf.flip();
        System.out.println("读到：" + StandardCharsets.UTF_8.decode(partBuf)); // CCCC

        // ============================================================
        // 第六步：在文件末尾追加内容
        // ============================================================
        System.out.println("\n=== 第六步：跳到末尾，追加内容 ===");

        // channel.size()：文件总字节数，跳到这里就是末尾
        channel.position(channel.size());
        ByteBuffer appendBuf = ByteBuffer.wrap(
                " EEEE".getBytes(StandardCharsets.UTF_8));
        channel.write(appendBuf);

        // 最终验证
        channel.position(0);
        ByteBuffer finalBuf = ByteBuffer.allocate((int) channel.size());
        channel.read(finalBuf);
        finalBuf.flip();
        System.out.println("最终内容：" + StandardCharsets.UTF_8.decode(finalBuf));
        // 期望：AAAA XXXX CCCC DDDD EEEE

        // 关闭（关 channel 会自动关 raf）
        channel.close();
        raf.close();
        new File(path).delete();

        /*
         * 总结：Channel 双向性的两个体现
         *
         * 1. 同一个对象既能 read() 又能 write()
         *    流做不到：InputStream 只能读，OutputStream 只能写
         *
         * 2. 通过 position() 随意跳转，实现随机访问
         *    流做不到：流是单向水管，只能从一端流向另一端
         *    Channel 像一个可以随意移动光标的编辑器
         *
         * 什么场景用 FileChannel 的双向随机访问？
         *   - 数据库文件：频繁修改文件中间某几个字节
         *   - 断点续传：记录已下载的字节数，下次从断点继续写
         *   - 二进制协议文件：固定格式，需要读头部信息再决定从哪里读数据
         *   - 文件索引：先写内容，再回头修改开头的索引/偏移量
         */
    }
}
