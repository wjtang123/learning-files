#!/usr/bin/env bash
# ============================================================
# cleanup.sh  清理所有演示资源
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"

title "清理所有演示资源"

# 清理 Docker 资源
step "清理 Docker 演示容器和镜像"
docker rm -f $(docker ps -aq --filter "name=demo\|ns-demo\|overlay\|cgroup\|net-\|nginx-port\|vol-" 2>/dev/null) 2>/dev/null || true
docker rmi -f demo-bad demo-good demo-go-bad demo-go-good demo-apt-bad demo-apt-good 2>/dev/null || true
docker volume rm $(docker volume ls -q --filter "name=demo") 2>/dev/null || true
docker network rm $(docker network ls -q --filter "name=demo") 2>/dev/null || true

# 清理 Docker Compose
step "清理 Compose 项目"
for dir in "$SCRIPT_DIR"/docker-advanced/*/; do
  [[ -f "$dir/docker-compose.yml" ]] && \
    docker compose -f "$dir/docker-compose.yml" down -v 2>/dev/null || true
done

# 清理 Kubernetes namespace
step "清理 Kubernetes 演示 namespace"
kubectl get ns 2>/dev/null | grep -E "demo-|svc-demo|arch-demo|ops-demo" | \
  awk '{print $1}' | xargs -r kubectl delete namespace --wait=false 2>/dev/null || true

ok "清理完成！"
