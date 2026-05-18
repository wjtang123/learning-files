#!/usr/bin/env bash
# ============================================================
# 04-network.sh  Docker 四种网络模式演示
# 知识点：bridge/host/none/overlay + DNS + iptables + veth pair
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require docker

title "第四章：Docker 网络模式深度演示"

# ──────────────────────────────────────────────────────────
step "实验 1：bridge 模式 —— 默认网络，veth pair + docker0"
# ──────────────────────────────────────────────────────────
explain "Bridge 模式：容器通过 veth pair 连接 docker0 网桥，通过 iptables DNAT 对外"

echo ""
echo -e "  ${CYAN}宿主机的 docker0 网桥：${NC}"
cmd "ip addr show docker0"
ip addr show docker0 2>/dev/null || ifconfig docker0 2>/dev/null || \
  docker network inspect bridge | jq '.[0].IPAM'

CB1="net-bridge-1-$$"
CB2="net-bridge-2-$$"
docker run -d --name "$CB1" --network bridge alpine sleep 600 > /dev/null
docker run -d --name "$CB2" --network bridge alpine sleep 600 > /dev/null
register_cleanup "docker rm -f $CB1 $CB2"

echo ""
echo -e "  ${CYAN}容器1 的网络接口：${NC}"
cmd "docker exec $CB1 ip addr"
docker exec "$CB1" ip addr

echo ""
echo -e "  ${CYAN}容器1 → 容器2 通信（通过 IP）：${NC}"
CB2_IP=$(docker inspect --format '{{.NetworkSettings.IPAddress}}' "$CB2")
cmd "docker exec $CB1 ping -c 2 $CB2_IP"
docker exec "$CB1" ping -c 2 "$CB2_IP" 2>/dev/null | tail -3

echo ""
explain "默认 bridge 网络：只能 IP 互通，不支持服务名 DNS 解析"
explain "自定义 bridge 网络：支持服务名 DNS！"

echo ""
echo -e "  ${CYAN}宿主机 veth pair（对应容器网卡的另一端）：${NC}"
cmd "ip link | grep -E 'veth|docker'"
ip link show 2>/dev/null | grep -E 'veth|docker' | head -10 || true

echo ""
echo -e "  ${CYAN}iptables DNAT 规则（端口映射的秘密）：${NC}"
cmd "iptables -t nat -L DOCKER --line-numbers 2>/dev/null | head -15"
iptables -t nat -L DOCKER --line-numbers 2>/dev/null | head -15 || \
  warn "需要 root 权限查看 iptables"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：自定义 bridge 网络 —— 服务名 DNS 解析"
# ──────────────────────────────────────────────────────────
explain "自定义 bridge 网络内置 Docker DNS（127.0.0.11）"
explain "可以直接用容器名/服务名访问，不需要知道 IP"

docker network create demo-net-$$ > /dev/null
register_cleanup "docker network rm demo-net-$$"

CD1="dns-server-$$"
CD2="dns-client-$$"
docker run -d --name "$CD1" --network "demo-net-$$" \
  --hostname my-server alpine sleep 600 > /dev/null
docker run -d --name "$CD2" --network "demo-net-$$" \
  alpine sleep 600 > /dev/null
register_cleanup "docker rm -f $CD1 $CD2"

echo ""
echo -e "  ${CYAN}查看容器内的 DNS 配置：${NC}"
cmd "docker exec $CD2 cat /etc/resolv.conf"
docker exec "$CD2" cat /etc/resolv.conf

echo ""
echo -e "  ${CYAN}用容器名直接访问（DNS 解析）：${NC}"
cmd "docker exec $CD2 nslookup $CD1"
docker exec "$CD2" nslookup "$CD1" 2>/dev/null | head -8

cmd "docker exec $CD2 ping -c 2 $CD1"
docker exec "$CD2" ping -c 2 "$CD1" 2>/dev/null | tail -3

echo ""
explain "/etc/resolv.conf 中 nameserver 127.0.0.11 = Docker 内嵌 DNS 服务器"
explain "它把容器名/服务名解析成对应容器 IP，这是 Compose 服务发现的基础"

pause

# ──────────────────────────────────────────────────────────
step "实验 3：host 模式 —— 直接使用宿主机网络栈"
# ──────────────────────────────────────────────────────────
explain "host 模式：容器和宿主机共享同一个 net namespace"
explain "优势：零转发开销，性能最好。代价：无网络隔离"

echo ""
echo -e "  ${CYAN}host 模式容器的网络接口（和宿主机完全相同）：${NC}"
cmd "docker run --rm --network host alpine ip addr"
docker run --rm --network host alpine ip addr 2>/dev/null | head -20

echo ""
echo -e "  ${CYAN}对比 bridge 模式的网络接口：${NC}"
cmd "docker run --rm --network bridge alpine ip addr"
docker run --rm --network bridge alpine ip addr 2>/dev/null | head -10

echo ""
explain "host 模式适合：网络密集型应用（监控 agent、高频交易）"
explain "风险：容器监听的端口直接暴露在宿主机上，端口可能冲突"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：none 模式 —— 完全隔离"
# ──────────────────────────────────────────────────────────
explain "none 模式：只有 lo 回环接口，无法访问任何外部网络"

echo ""
cmd "docker run --rm --network none alpine ip addr"
docker run --rm --network none alpine ip addr 2>/dev/null

echo ""
cmd "docker run --rm --network none alpine ping -c 1 8.8.8.8"
docker run --rm --network none alpine ping -c 1 8.8.8.8 2>&1 | tail -3 || \
  echo "  ping: bad address '8.8.8.8' (网络完全隔离)"

explain "适合场景：数据处理（只读写 Volume，不需要网络）、安全合规隔离"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：端口映射 —— -p 的工作原理"
# ──────────────────────────────────────────────────────────
explain "-p 宿主机端口:容器端口 → Docker 在 iptables 写入 DNAT 规则"

echo ""
CNGINX="nginx-port-$$"
docker run -d --name "$CNGINX" -p 18080:80 nginx:alpine > /dev/null
register_cleanup "docker rm -f $CNGINX"

echo -e "  ${CYAN}测试端口映射（宿主机 18080 → 容器 80）：${NC}"
cmd "curl -s http://localhost:18080 | head -5"
sleep 1
curl -s http://localhost:18080 2>/dev/null | head -5 || \
  warn "curl 失败，尝试 wget..."
wget -qO- http://localhost:18080 2>/dev/null | head -5 || true

echo ""
echo -e "  ${CYAN}对应的 iptables DNAT 规则：${NC}"
cmd "iptables -t nat -L -n | grep 18080"
iptables -t nat -L -n 2>/dev/null | grep -E "18080|DNAT" | head -5 || \
  warn "查看 iptables 需要 root 权限"

echo ""
explain "端口映射不需要 EXPOSE 也能工作"
explain "EXPOSE 只是文档声明，对实际端口映射无任何影响"

pause

# ──────────────────────────────────────────────────────────
step "实验 6：网络连通性矩阵 —— 不同网络间的隔离"
# ──────────────────────────────────────────────────────────
explain "不同 bridge 网络的容器默认完全隔离（iptables 阻断跨网桥转发）"
explain "要跨网络通信：docker network connect 或多网络容器"

docker network create demo-netA-$$ > /dev/null
docker network create demo-netB-$$ > /dev/null
register_cleanup "docker network rm demo-netA-$$ demo-netB-$$"

CA="net-a-$$"
CB="net-b-$$"
CMULTI="net-multi-$$"

docker run -d --name "$CA" --network "demo-netA-$$" alpine sleep 600 > /dev/null
docker run -d --name "$CB" --network "demo-netB-$$" alpine sleep 600 > /dev/null
docker run -d --name "$CMULTI" --network "demo-netA-$$" alpine sleep 600 > /dev/null
register_cleanup "docker rm -f $CA $CB $CMULTI"

# 让 multi 同时加入 netB
docker network connect "demo-netB-$$" "$CMULTI"

# 取 CA 在 netA 网络中的 IP（明确指定网络名避免歧义）
NET_A="demo-netA-$$"
CA_IP=$(docker inspect "$CA"   --format "{{(index .NetworkSettings.Networks \"${NET_A}\").IPAddress}}" 2>/dev/null ||   docker inspect "$CA"   --format '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' | awk '{print $1}')

echo ""
echo -e "  ${RED}跨网络隔离测试（netA → netB，应该失败）：${NC}"
cmd "docker exec $CB ping -c 1 $CA_IP"
docker exec "$CB" ping -c 1 "$CA_IP" -W 2 2>&1 | tail -3 || echo "  ❌ 无法连通（隔离生效）"

echo ""
echo -e "  ${GREEN}多网络容器可以跨网络通信（应该成功）：${NC}"
cmd "docker exec $CMULTI ping -c 2 $CA_IP"
docker exec "$CMULTI" ping -c 2 "$CA_IP" 2>/dev/null | tail -3

echo ""
explain "docker network connect <网络> <容器> → 容器加入第二个网络，成为网络间的桥"

run_cleanup

title "✅ Docker 网络模式演示完成！"
echo "  核心记忆："
echo "  • bridge：默认，veth+docker0+iptables，需要-p端口映射"
echo "  • host：共享宿主机网络栈，性能最好，无隔离"
echo "  • none：完全隔离，仅 lo"
echo "  • overlay：VXLAN 跨主机（Swarm/k8s 用）"
echo "  • 自定义 bridge 网络 = 内置 DNS = 服务名直接访问"
echo ""
echo "  👉 下一步：bash docker-advanced/05-compose/up.sh"
