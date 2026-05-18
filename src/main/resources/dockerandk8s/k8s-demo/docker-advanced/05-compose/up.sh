#!/usr/bin/env bash
# ============================================================
# 05-compose/up.sh  Docker Compose 多服务编排演示
# 知识点：depends_on+healthcheck、DNS服务发现、volume、网络隔离
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require docker
WORK_DIR="$(dirname "${BASH_SOURCE[0]}")"

title "第五章：Docker Compose 多服务编排"

# ── 创建演示应用 ──────────────────────────────────────────
mkdir -p "$WORK_DIR/webapp"

cat > "$WORK_DIR/webapp/app.py" << 'PYEOF'
import os, time, redis, psycopg2
from flask import Flask, jsonify

app = Flask(__name__)

def get_redis():
    return redis.Redis(host=os.getenv('REDIS_HOST','cache'),
                       port=6379, decode_responses=True)

def get_db():
    import time
    for i in range(5):
        try:
            return psycopg2.connect(
                host=os.getenv('DB_HOST','db'),
                user=os.getenv('DB_USER','demo'),
                password=os.getenv('DB_PASS','demo123'),
                dbname=os.getenv('DB_NAME','demo'),
                connect_timeout=3
            )
        except Exception as e:
            if i == 4:
                raise
            time.sleep(1)

@app.route('/')
def index():
    return jsonify({
        'status': 'ok',
        'message': 'Compose Demo App',
        'services': ['web', 'db', 'cache']
    })

@app.route('/health')
def health():
    return 'OK\n', 200

@app.route('/cache-test')
def cache_test():
    r = get_redis()
    count = r.incr('visits')
    return jsonify({'visits': count, 'from': 'redis'})

@app.route('/db-test')
def db_test():
    try:
        conn = get_db()
        cur = conn.cursor()
        cur.execute("SELECT version()")
        ver = cur.fetchone()[0]
        conn.close()
        return jsonify({'db_version': ver, 'status': 'connected'})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
PYEOF

cat > "$WORK_DIR/webapp/requirements.txt" << 'EOF'
flask==3.0.0
redis==5.0.1
psycopg2-binary==2.9.9
EOF

cat > "$WORK_DIR/webapp/Dockerfile" << 'EOF'
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser
EXPOSE 5000
CMD ["python", "app.py"]
EOF

# ── docker-compose.yml ────────────────────────────────────
cat > "$WORK_DIR/docker-compose.yml" << 'EOF'
version: "3.9"

services:

  # ── 应用服务 ──────────────────────────────────────────
  web:
    build: ./webapp
    ports:
      - "15000:5000"
    environment:
      - DB_HOST=db          # 直接用服务名，Docker DNS 自动解析
      - DB_USER=demo
      - DB_PASS=demo123
      - DB_NAME=demo
      - REDIS_HOST=cache
    depends_on:
      db:
        condition: service_healthy     # ⚡ 等健康检查通过才启动
      cache:
        condition: service_healthy
    networks:
      - app-net
    restart: on-failure:5
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:5000/health')"]
      interval: 10s
      timeout: 5s
      retries: 3

  # ── 数据库服务 ────────────────────────────────────────
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: demo
      POSTGRES_PASSWORD: demo123
      POSTGRES_DB: demo
    volumes:
      - pgdata:/var/lib/postgresql/data    # 具名 volume，数据持久化
    networks:
      - app-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U demo"]    # ⚡ 就绪才算健康
      interval: 5s
      timeout: 3s
      retries: 5
      start_period: 10s

  # ── 缓存服务 ──────────────────────────────────────────
  cache:
    image: redis:7-alpine
    command: redis-server --appendonly yes --maxmemory 128mb
    volumes:
      - redisdata:/data
    networks:
      - app-net
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 3

networks:
  app-net:
    driver: bridge           # 自定义 bridge 网络，支持服务名 DNS

volumes:
  pgdata:                    # Docker 管理，/var/lib/docker/volumes/
  redisdata:
EOF

# ── 演示 ──────────────────────────────────────────────────
step "展示 docker-compose.yml 结构"
cmd "cat docker-compose.yml"
cat "$WORK_DIR/docker-compose.yml"

pause

step "启动所有服务（按依赖顺序）"
explain "Compose 会：① 构建镜像 ② 创建网络+volume ③ 按依赖顺序启动服务"

cmd "docker compose -f $WORK_DIR/docker-compose.yml up -d --build"
docker compose -f "$WORK_DIR/docker-compose.yml" up -d --build 2>&1

register_cleanup "docker compose -f $WORK_DIR/docker-compose.yml down -v"

echo ""
info "等待所有服务就绪..."
sleep 5

step "查看服务状态"
cmd "docker compose -f $WORK_DIR/docker-compose.yml ps"
docker compose -f "$WORK_DIR/docker-compose.yml" ps

pause

step "验证 DNS 服务发现 —— web 用服务名访问 db 和 cache"
explain "web 容器可以直接 ping db、ping cache，无需知道 IP"

cmd "docker compose -f $WORK_DIR/docker-compose.yml exec web nslookup db"
docker compose -f "$WORK_DIR/docker-compose.yml" \
  exec web nslookup db 2>/dev/null | head -8 || true

cmd "docker compose -f $WORK_DIR/docker-compose.yml exec web nslookup cache"
docker compose -f "$WORK_DIR/docker-compose.yml" \
  exec cache nslookup db 2>/dev/null | head -5 || true

pause

step "测试应用端点"

echo -e "  ${CYAN}主页：${NC}"
curl -s http://localhost:15000/ 2>/dev/null | python3 -m json.tool 2>/dev/null || \
  curl -s http://localhost:15000/ 2>/dev/null

echo ""
echo -e "  ${CYAN}Redis 缓存测试（每次访问计数+1）：${NC}"
for i in 1 2 3; do
  curl -s http://localhost:15000/cache-test 2>/dev/null
  sleep 0.5
done

echo ""
echo -e "  ${CYAN}PostgreSQL 连接测试：${NC}"
curl -s http://localhost:15000/db-test 2>/dev/null | \
  python3 -m json.tool 2>/dev/null || curl -s http://localhost:15000/db-test

pause

step "演示 depends_on 的陷阱"
explain "depends_on 没有 condition: service_healthy 时，只等容器启动，不等就绪"
explain "pg 容器启动了，但数据库进程初始化需要几秒，web 可能连接失败"
explain "解决方案：healthcheck + condition: service_healthy（已在 compose 里配置）"

cmd "docker compose -f $WORK_DIR/docker-compose.yml logs web | tail -10"
docker compose -f "$WORK_DIR/docker-compose.yml" logs web 2>/dev/null | tail -10

pause

step "演示 Scale —— 水平扩容"
explain "docker compose up --scale web=3 启动3个 web 实例"

cmd "docker compose -f $WORK_DIR/docker-compose.yml up -d --scale web=2"
docker compose -f "$WORK_DIR/docker-compose.yml" \
  up -d --scale web=2 2>&1 | tail -5

echo ""
cmd "docker compose -f $WORK_DIR/docker-compose.yml ps"
docker compose -f "$WORK_DIR/docker-compose.yml" ps

pause

step "演示 docker compose exec vs run"
explain "exec：在运行中的容器里执行（共享上下文）"
explain "run --rm：启动新容器执行完销毁（一次性任务）"

echo -e "  ${CYAN}exec 进入 db 运行 psql：${NC}"
cmd "docker compose -f $WORK_DIR/docker-compose.yml exec db psql -U demo -c '\\\\l'"
docker compose -f "$WORK_DIR/docker-compose.yml" \
  exec db psql -U demo -c '\l' 2>/dev/null | head -10 || true

echo ""
echo -e "  ${CYAN}run --rm 运行一次性命令：${NC}"
cmd "docker compose -f $WORK_DIR/docker-compose.yml run --rm web python -c 'print(\"一次性任务完成\")'"
docker compose -f "$WORK_DIR/docker-compose.yml" \
  run --rm web python -c 'print("一次性任务完成")' 2>/dev/null || true

pause

step "演示 Volume 持久化"
explain "db 数据存在具名 volume pgdata，即使容器删除数据也在"

cmd "docker volume inspect \$(docker compose -f $WORK_DIR/docker-compose.yml config --volumes | head -1)"
docker volume ls | grep -E "pgdata|redisdata" | head -5

# 清理
run_cleanup
rm -rf "$WORK_DIR/webapp"

title "✅ Docker Compose 演示完成！"
echo "  核心记忆："
echo "  • depends_on + healthcheck + condition: service_healthy = 真正的就绪等待"
echo "  • 自定义网络 = 服务名 DNS = 应用直接用 db、cache 访问服务"
echo "  • 具名 volume = 数据持久化，容器删了数据还在"
echo "  • exec = 进入运行中容器；run --rm = 一次性新容器"
echo ""
echo "  👉 下一步：bash k8s-core/08-pod-deploy.sh"
