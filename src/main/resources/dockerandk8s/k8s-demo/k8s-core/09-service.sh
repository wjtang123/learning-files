#!/usr/bin/env bash
# ============================================================
# 09-service.sh  Service 四种类型完整演示
# 知识点：ClusterIP/NodePort/Headless/ExternalName + kube-proxy + DNS
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require kubectl

NS="svc-demo-$$"
title "第九章：Service 四种类型完整演示"
kubectl create namespace "$NS" 2>/dev/null || true
register_cleanup "kubectl delete namespace $NS --wait=false 2>/dev/null"

# ── 先创建后端 Deployment ─────────────────────────────────
kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: backend
  template:
    metadata:
      labels:
        app: backend
    spec:
      containers:
      - name: server
        image: nginx:alpine
        ports:
        - containerPort: 80
        env:
        - name: MY_POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        lifecycle:
          postStart:
            exec:
              command: ["/bin/sh", "-c",
                "echo Pod:$MY_POD_NAME > /usr/share/nginx/html/index.html"]
        resources:
          requests: {memory: "16Mi", cpu: "10m"}
          limits:  {memory: "32Mi", cpu: "50m"}
EOF

info "等待后端 Pod 就绪..."
kubectl rollout status deployment/backend -n "$NS" --timeout=90s

# ──────────────────────────────────────────────────────────
step "实验 1：ClusterIP —— 集群内稳定访问入口"
# ──────────────────────────────────────────────────────────
explain "ClusterIP 分配集群内 VIP，kube-proxy 用 iptables/IPVS 做负载均衡"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: backend-clusterip
spec:
  type: ClusterIP          # 默认类型
  selector:
    app: backend
  ports:
  - name: http
    port: 80               # Service 端口
    targetPort: 80       # Pod 端口
EOF

echo ""
cmd "kubectl get service backend-clusterip -n $NS"
kubectl get service backend-clusterip -n "$NS"

CIP=$(kubectl get svc backend-clusterip -n "$NS" \
  -o jsonpath='{.spec.clusterIP}')
echo ""
explain "ClusterIP=$CIP，只在集群内可访问"

echo ""
echo -e "  ${CYAN}从集群内访问（用临时 Pod 测试）：${NC}"
cmd "kubectl run test-curl --rm -i --image=curlimages/curl -n $NS -- curl -s http://backend-clusterip"
kubectl run test-curl --rm -i --image=curlimages/curl \
  -n "$NS" --restart=Never \
  -- curl -s http://backend-clusterip 2>/dev/null || \
  kubectl run test-curl --rm -i --image=busybox \
  -n "$NS" --restart=Never \
  -- wget -qO- "http://$CIP" 2>/dev/null || true

echo ""
explain "多次访问会轮询到不同 Pod（负载均衡）"
for i in 1 2 3; do
  kubectl run "test-$i" --rm -i --image=busybox \
    -n "$NS" --restart=Never \
    -- wget -qO- "http://$CIP" 2>/dev/null &
done
wait
echo ""

pause

# ──────────────────────────────────────────────────────────
step "实验 2：Endpoints —— Service 后端列表"
# ──────────────────────────────────────────────────────────
explain "Endpoints 对象记录 Service 对应的所有后端 Pod IP:Port"
explain "Pod readinessProbe 失败 → 自动从 Endpoints 摘除"

cmd "kubectl get endpoints backend-clusterip -n $NS"
kubectl get endpoints backend-clusterip -n "$NS"

echo ""
echo -e "  ${CYAN}Endpoints 详情：${NC}"
kubectl describe endpoints backend-clusterip -n "$NS" | head -20

pause

# ──────────────────────────────────────────────────────────
step "实验 3：NodePort —— 从集群外访问"
# ──────────────────────────────────────────────────────────
explain "NodePort 在所有节点开放同一端口，任意节点IP:NodePort 都能访问"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: backend-nodeport
spec:
  type: NodePort
  selector:
    app: backend
  ports:
  - port: 80
    targetPort: 80
    nodePort: 30090      # 30000-32767
EOF

cmd "kubectl get service backend-nodeport -n $NS"
kubectl get service backend-nodeport -n "$NS"

NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}' 2>/dev/null || \
          minikube ip 2>/dev/null || hostname -I | awk '{print $1}')
echo ""
explain "集群外访问：curl http://$NODE_IP:30090"
cmd "curl -s http://$NODE_IP:30090"
curl -s "http://$NODE_IP:30090" 2>/dev/null || \
  wget -qO- "http://$NODE_IP:30090" 2>/dev/null || \
  warn "无法从当前位置访问，在集群节点上执行: curl http://$NODE_IP:30090"

echo ""
explain "NodePort 包含 ClusterIP：集群内也可以通过 ClusterIP:80 访问"
explain "生产不推荐：端口受限(30000-32767)，无健康检查，需自管节点IP"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：Headless Service —— StatefulSet 专用"
# ──────────────────────────────────────────────────────────
explain "clusterIP: None = Headless，DNS 直接解析到所有 Pod IP"
explain "StatefulSet 用它给每个 Pod 固定 DNS：pod-0.svc.ns.svc.cluster.local"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: backend-headless
spec:
  clusterIP: None          # ⚡ Headless 的关键：不分配 VIP
  selector:
    app: backend
  ports:
  - port: 80
    targetPort: 80
EOF

echo ""
cmd "kubectl get service backend-headless -n $NS"
kubectl get service backend-headless -n "$NS"

echo ""
echo -e "  ${CYAN}DNS 解析对比：${NC}"
echo -e "  ${YELLOW}ClusterIP Service → 返回 VIP（1个IP）：${NC}"
kubectl run dns-test --rm -i --image=busybox -n "$NS" --restart=Never \
  -- nslookup backend-clusterip 2>/dev/null | grep -E "Address|Name" || true

echo ""
echo -e "  ${GREEN}Headless Service → 返回所有 Pod IP（3个IP）：${NC}"
kubectl run dns-test2 --rm -i --image=busybox -n "$NS" --restart=Never \
  -- nslookup backend-headless 2>/dev/null | grep -E "Address|Name" || true

explain "Headless DNS 返回多个 A 记录（每个 Pod 一个），客户端自行选择"
explain "gRPC 客户端必须用 Headless，因为 HTTP/2 长连接不会轮询"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：ExternalName —— DNS CNAME 别名"
# ──────────────────────────────────────────────────────────
explain "ExternalName 把外部域名包装成集群内 Service，零代理，纯 DNS"
explain "典型用途：服务迁移过渡（先用 ExternalName 指向旧地址，迁移后改 selector）"

kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: v1
kind: Service
metadata:
  name: external-api
spec:
  type: ExternalName
  externalName: httpbin.org    # 解析到外部域名
  ports:
  - port: 80
EOF

cmd "kubectl get service external-api -n $NS"
kubectl get service external-api -n "$NS"

echo ""
echo -e "  ${CYAN}DNS 解析 ExternalName Service（CNAME 记录）：${NC}"
kubectl run dns-ext --rm -i --image=busybox -n "$NS" --restart=Never \
  -- nslookup external-api 2>/dev/null | grep -E "canonical|Address|Name" || true

echo ""
explain "集群内代码写 external-api 访问，迁移时只改 Service 定义，代码零修改"

pause

# ──────────────────────────────────────────────────────────
step "实验 6：kube-proxy 模式 —— iptables 规则验证"
# ──────────────────────────────────────────────────────────
explain "iptables 模式：每个 Service 对应一批 DNAT 规则，随机选后端 Pod"

echo ""
cmd "kubectl get configmap kube-proxy -n kube-system -o yaml 2>/dev/null | grep mode"
kubectl get configmap kube-proxy -n kube-system -o yaml 2>/dev/null | \
  grep -A2 "mode:" | head -5 || warn "kube-proxy 配置需要集群权限"

echo ""
echo -e "  ${CYAN}iptables NAT 规则中的 Service（需要节点 root）：${NC}"
cmd "iptables -t nat -L -n | grep $NS | head -10"
# 在节点上执行更清晰，这里给出命令示范
echo "  提示：在节点上运行: sudo iptables -t nat -L -n | grep KUBE"

pause

# ──────────────────────────────────────────────────────────
step "实验 7：Service + Ingress 架构 —— 最佳实践"
# ──────────────────────────────────────────────────────────
explain "生产最佳实践：1个 LoadBalancer(Ingress Controller) + N个 ClusterIP Service"
explain "所有 HTTP 流量通过 Ingress 路由，省去每个应用独占 LB 的费用"

cat << 'ARCH'

  ┌──────────────────────────────────────────────────────────┐
  │  外部流量                                                 │
  │       ↓                                                   │
  │  云 LoadBalancer（1个，贵）                               │
  │       ↓                                                   │
  │  Ingress Controller Pod（Nginx/Traefik）                  │
  │       ↓ 按域名/路径路由                                   │
  │  ┌────────────┐  ┌────────────┐  ┌────────────┐          │
  │  │ ClusterIP  │  │ ClusterIP  │  │ ClusterIP  │          │
  │  │ user-svc   │  │ order-svc  │  │ pay-svc    │          │
  │  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘          │
  │        ↓               ↓               ↓                 │
  │      Pods            Pods            Pods                │
  └──────────────────────────────────────────────────────────┘

ARCH

if kubectl get ingressclass 2>/dev/null | grep -q nginx; then
  kubectl apply -n "$NS" -f - << 'EOF'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: demo-ingress
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
spec:
  ingressClassName: nginx
  rules:
  - host: demo.local
    http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: backend-clusterip
            port:
              number: 80
EOF
  cmd "kubectl get ingress -n $NS"
  kubectl get ingress -n "$NS"
else
  warn "Ingress Controller 未安装，跳过 Ingress 演示"
  warn "运行: minikube addons enable ingress  来启用"
fi

run_cleanup

title "✅ Service 四种类型演示完成！"
echo "  核心记忆："
echo "  • ClusterIP  = 集群内 VIP，iptables/IPVS 负载均衡（最常用）"
echo "  • NodePort   = ClusterIP + 节点高位端口（开发测试）"
echo "  • LB         = NodePort + 云厂商 LB 公网 IP（成本高）"
echo "  • Headless   = 无 VIP，DNS 返回所有 Pod IP（StatefulSet/gRPC）"
echo "  • ExternalName = 纯 DNS CNAME，零代理（迁移过渡）"
echo "  • 生产：LB(Ingress) + ClusterIP，一个 LB 承载所有 HTTP 应用"
echo ""
echo "  👉 下一步：bash k8s-core/10-configmap-secret.sh"
