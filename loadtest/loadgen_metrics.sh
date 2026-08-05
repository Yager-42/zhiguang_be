#!/usr/bin/env bash
# 压测机自身资源采样器（在压测机宿主机上运行，不要在容器内跑：/proc 是宿主视角）。
# 每 SAMPLE_INTERVAL 秒输出一行 TSV 到 stdout（运行方重定向到 results/metrics/loadgen-*.tsv）。
# 判定用途：压测机 CPU > 80% 或网络打满 → 测量失真，先加压测机资源，不能判 SUT 瓶颈。
# 依赖 /proc（Linux/WSL/Git-Bash 可用）、netstat；命令失败输出 NA 不中断。
set -uo pipefail

INTERVAL="${SAMPLE_INTERVAL:-15}"
DURATION="${SAMPLE_DURATION:-21600}" # 6h 安全上限

echo -e "ts\tcpu_pct\tmem_pct\tnet_rx_kbps\tnet_tx_kbps\testab\tclose_wait\ttime_wait\tfile_nr"

prev_total=0
prev_idle=0
prev_rx=0
prev_tx=0

read_cpu() {
  local line
  line=$(grep -m1 '^cpu ' /proc/stat 2>/dev/null) || return 1
  # shellcheck disable=SC2086
  set -- $line
  # $1=cpu $2=user $3=nice $4=system $5=idle $6=iowait $7=irq $8=softirq $9=steal
  prev_total=$(( $2 + $3 + $4 + $5 + $6 + $7 + $8 + $9 ))
  prev_idle=$(( $5 + $6 ))
}

read_net() {
  prev_rx=$(awk 'NR>2 && $1 !~ /^lo:/ {gsub(":", "", $1); rx += $2} END {print rx + 0}' /proc/net/dev 2>/dev/null)
  prev_tx=$(awk 'NR>2 && $1 !~ /^lo:/ {gsub(":", "", $1); tx += $10} END {print tx + 0}' /proc/net/dev 2>/dev/null)
}

read_cpu || prev_total=0
read_net

for ((s = 0; s < DURATION; s += INTERVAL)); do
  sleep "$INTERVAL"

  if [ "$prev_total" -eq 0 ]; then
    # 首次采样仅建立基线，不输出
    read_cpu || prev_total=0
    read_net
    continue
  fi

  ts=$(date +%H:%M:%S)

  cpu_pct=NA
  total=0
  idle=0
  line=$(grep -m1 '^cpu ' /proc/stat 2>/dev/null) || true
  if [ -n "$line" ]; then
    # shellcheck disable=SC2086
    set -- $line
    total=$(( $2 + $3 + $4 + $5 + $6 + $7 + $8 + $9 ))
    idle=$(( $5 + $6 ))
    if [ "$total" -gt "$prev_total" ]; then
      cpu_pct=$(( (100 * (total - prev_total - idle + prev_idle)) / (total - prev_total) ))
    fi
  fi

  mem_pct=NA
  mem=$(grep -E '^Mem(Total|Available):' /proc/meminfo 2>/dev/null | awk '{print $2}')
  set -- $mem
  if [ "$#" -eq 2 ]; then
    mem_pct=$(( 100 * ($1 - $2) / $1 ))
  fi

  rx_kbps=NA
  tx_kbps=NA
  rx=0
  tx=0
  rx=$(awk 'NR>2 && $1 !~ /^lo:/ {gsub(":", "", $1); r += $2} END {print r + 0}' /proc/net/dev 2>/dev/null)
  tx=$(awk 'NR>2 && $1 !~ /^lo:/ {gsub(":", "", $1); t += $10} END {print t + 0}' /proc/net/dev 2>/dev/null)
  if [ -n "$rx" ] && [ -n "$prev_rx" ]; then
    rx_kbps=$(( (rx - prev_rx) * 8 / INTERVAL / 1000 ))
    tx_kbps=$(( (tx - prev_tx) * 8 / INTERVAL / 1000 ))
  fi

  estab=$(netstat -an 2>/dev/null | awk '$NF == "ESTABLISHED" {c++} END {print c + 0}')
  close_wait=$(netstat -an 2>/dev/null | awk '$NF == "CLOSE_WAIT" {c++} END {print c + 0}')
  time_wait=$(netstat -an 2>/dev/null | awk '$NF == "TIME_WAIT" {c++} END {print c + 0}')

  file_nr=$(awk '{print $1}' /proc/sys/fs/file-nr 2>/dev/null)

  echo -e "${ts}\t${cpu_pct}\t${mem_pct}\t${rx_kbps}\t${tx_kbps}\t${estab:-NA}\t${close_wait:-NA}\t${time_wait:-NA}\t${file_nr:-NA}"

  prev_total=$total
  prev_idle=$idle
  prev_rx=$rx
  prev_tx=$tx
done
